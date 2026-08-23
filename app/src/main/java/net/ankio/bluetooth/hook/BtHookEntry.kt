package net.ankio.bluetooth.hook

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import net.ankio.bluetooth.model.SimulateMode
import net.ankio.bluetooth.utils.ByteUtils
import net.ankio.bluetooth.utils.HookLogManager
import net.ankio.bluetooth.utils.PrefKeys
import java.io.Serializable

/**
 * 传统 Xposed 入口：专用于系统蓝牙进程 `com.android.bluetooth` 的本机模拟注入。
 *
 * 说明：AnkioTomas 2.0.0 原先用 `net.ankio.xposed.lib` 框架（[BluetoothXposedEntry]）注入蓝牙进程，
 * 但在部分 ColorOS 16 金标设备上，LSPosed 不会将该框架入口注入蓝牙进程，导致本机模拟完全失效。
 * 而 ShadowSmallBaby 1.0.9 用传统 `IXposedHookLoadPackage` 入口（Main）在该类机型上可正常注入。
 * 因此这里改用传统入口（IXposedHookLoadPackage），逻辑上与 shadow 1.0.9 对齐：
 *  - Android <= 15：Hook GattService.start/stop
 *  - Android 16+：Hook GattService 构造器 + cleanup
 *  - 每次广播实时读取 simulate_mode（仅 Self 才注入），切换开关无需重启蓝牙即生效
 *
 * 该入口与框架入口 [BluetoothXposedEntry]（负责 app 自身的 SelfHooker）共存于 xposed_init 中。
 */
class BtHookEntry : IXposedHookLoadPackage {

    private val tag = "BluetoothDebug"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null || lpparam.packageName != "com.android.bluetooth") return
        HookLogManager.d(tag, "BtHookEntry loaded, package=${lpparam.packageName}")

        // 无论当前 simulate_mode 如何都安装生命周期 Hook；
        // 是否注入在每次广播时实时判断（见 [ScanBroadcaster.run]）。
        val gattClass = XposedHelpers.findClass(GATT_SERVICE, lpparam.classLoader)
        val adapterClass = XposedHelpers.findClass(ADAPTER_SERVICE, lpparam.classLoader)

        // 依次尝试各生命周期绑定方式；单个失败不影响其它尝试，保证尽可能装上。
        // OS <= Android 15：GattService.start/stop
        if (hasMethod(gattClass, "start")) {
            installStartStop(gattClass, "start", "stop")
            HookLogManager.d(tag, "Installed start/stop hooks")
        } else if (hasMethod(gattClass, "initMiFeature")) {
            installStartStop(gattClass, "initMiFeature", "cleanup")
            HookLogManager.d(tag, "Installed initMiFeature/cleanup hooks")
        } else {
            // Android 16+：GattService 在构造完成后就绪，停止仍走 cleanup
            installConstructorStop(gattClass, adapterClass)
            HookLogManager.d(tag, "Installed constructor/cleanup hooks")
        }
    }

    private fun installStartStop(gattClass: Class<*>, start: String, stop: String) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                gattClass,
                start,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        attachBroadcaster(param.thisObject)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                gattClass,
                stop,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        detachBroadcaster(param.thisObject)
                    }
                },
            )
        }.onFailure { e ->
            HookLogManager.e(tag, "hook start/stop failed: ${e.message}", e)
        }
    }

    private fun installConstructorStop(gattClass: Class<*>, adapterClass: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookConstructor(
                gattClass,
                adapterClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        attachBroadcaster(param.thisObject)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                gattClass,
                "cleanup",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        detachBroadcaster(param.thisObject)
                    }
                },
            )
        }.onFailure { e ->
            HookLogManager.e(tag, "hook constructor/cleanup failed: ${e.message}", e)
        }
    }

    private fun attachBroadcaster(gattService: Any) {
        var handler = XposedHelpers.getAdditionalInstanceField(gattService, HANDLER_KEY) as Handler?
        if (handler == null) {
            handler = Handler(Looper.getMainLooper())
        }
        XposedHelpers.setAdditionalInstanceField(gattService, HANDLER_KEY, handler)
        val broadcast = ScanBroadcaster(gattService, handler)
        XposedHelpers.setAdditionalInstanceField(gattService, RUNNABLE_KEY, broadcast)
        handler.postDelayed(broadcast, INTERVAL_MS)
    }

    private fun detachBroadcaster(gattService: Any) {
        val handler = XposedHelpers.getAdditionalInstanceField(gattService, HANDLER_KEY) as? Handler
            ?: return
        val runnable = XposedHelpers.getAdditionalInstanceField(gattService, RUNNABLE_KEY) as? Runnable
            ?: return
        handler.removeCallbacks(runnable)
    }

    private class ScanBroadcaster(
        private val gattService: Any,
        private val handler: Handler,
    ) : Runnable {

        /** 首次成功解析后缓存，避免每 500ms 重复探测反射路径。 */
        private var scanPath: Pair<(Any) -> Any, Boolean>? = null

        override fun run() {
            // 实时判断模式：仅当本机模拟(Self)时才注入；切换开关无需重启蓝牙，下一个 tick 即生效。
            val mode = HookConfig.getString(PrefKeys.SIMULATE_MODE, "")
            if (mode == SimulateMode.Self.toString()) {
                if (scanPath == null) {
                    scanPath = resolveScanPath(gattService)
                }
                scanPath?.let { (getter, trailingMac) ->
                    val mac = HookConfig.getString(PrefKeys.PREF_MAC, DEFAULT_MAC)
                    val rssi = HookConfig.getString(PrefKeys.PREF_RSSI, DEFAULT_RSSI).toInt()
                    val advData = ByteUtils.hexStringToBytes(
                        HookConfig.getString(PrefKeys.PREF_DATA, DEFAULT_ADV_DATA),
                    )
                    try {
                        invokeScanResult(getter(gattService), mac, rssi, advData, trailingMac)
                    } catch (e: Throwable) {
                        HookLogManager.e(TAG, "Mock scan injection failed: ${e.message}", e)
                    }
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    companion object {
        const val TAG = "BluetoothDebug"

        private const val GATT_SERVICE = "com.android.bluetooth.gatt.GattService"
        private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"
        private const val HANDLER_KEY = "handler"
        private const val RUNNABLE_KEY = "runnable"
        private const val INTERVAL_MS = 500L
        private const val DEFAULT_MAC = "76:A7:8A:67:66:C9"
        private const val DEFAULT_RSSI = "-50"
        private const val DEFAULT_ADV_DATA =
            "02010403033CFE17FF0001B500024271A7B6000000C983926CB1011000000000000000000000000000000000000000000000000000000000000000000000"

        private fun hasMethod(clazz: Class<*>, name: String): Boolean =
            clazz.declaredMethods.any { it.name == name }

        private fun resolveScanPath(gattService: Any): Pair<(Any) -> Any, Boolean>? {
            val gattClass = gattService.javaClass
            val candidates = buildList {
                if (hasMethod(gattClass, "getScanController")) {
                    add("getScanController" to { s: Any -> XposedHelpers.callMethod(s, "getScanController") })
                }
                if (hasMethod(gattClass, "getTransitionalScanHelper")) {
                    add(
                        "getTransitionalScanHelper" to { s: Any ->
                            XposedHelpers.callMethod(s, "getTransitionalScanHelper")
                        },
                    )
                }
                add("GattService" to { s: Any -> s })
            }

            for ((name, getter) in candidates) {
                val target = try {
                    getter(gattService)
                } catch (_: Throwable) {
                    continue
                }
                val trailingMac = when (onScanResultParamCount(target)) {
                    11 -> true
                    10 -> false
                    else -> continue
                }
                HookLogManager.d(TAG, "Resolved scan target: $name")
                return getter to trailingMac
            }

            HookLogManager.e(
                TAG,
                "Unsupported device; export com.android.bluetooth and open a GitHub issue",
            )
            return null
        }

        private fun onScanResultParamCount(target: Any): Int? =
            target.javaClass.declaredMethods
                .firstOrNull { it.name == "onScanResult" }
                ?.parameterTypes
                ?.size

        private fun invokeScanResult(
            target: Any,
            mac: String,
            rssi: Int,
            advData: ByteArray,
            trailingMac: Boolean,
        ) {
            val params: Array<Serializable> = arrayOf(
                0x1b,
                0x00,
                mac,
                0x01,
                0x00,
                0xff,
                0x7f,
                rssi,
                0x00,
                advData,
                mac,
            )
            val args = if (trailingMac) params else params.copyOf(params.size - 1)
            XposedHelpers.callMethod(target, "onScanResult", *args)
        }
    }
}
