package net.ankio.bluetooth.hook

import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import net.ankio.bluetooth.utils.ByteUtils
import net.ankio.bluetooth.utils.HookLogManager
import net.ankio.bluetooth.utils.PrefKeys
import java.io.Serializable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Xposed 入口（libxposed 新版 API）：专用于系统蓝牙进程 `com.android.bluetooth` 的本机模拟注入。
 *
 * 对应 Shadow 1.0.9 的 Main，但开关改用本 2.0.0 分支的 SimulateMode.Self（simulate_mode 枚举）。
 * 与旧版 IXposedHookLoadPackage 入口等价，只是入口基类与 Hook 方式改为新版 API：
 * 元数据由 META-INF/xposed/java_init.list 声明，Hook 走 XposedInterface.hook(Executable).intercept()。
 *
 * 生命周期安装（无论当前是否开启模拟都会装，是否注入在每次广播时实时判断）：
 *  - OS <= Android 15：GattService.start/stop
 *  - Android 16+：GattService 构造器 + cleanup
 */
class Main : XposedModule() {

    /** 每个 GattService 实例上的广播器，替代旧版 XposedHelpers 的附加实例字段。 */
    private val attachments = WeakHashMap<Any, Attachment>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedApi.attach(this)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        XposedApi.attach(this)
        HookLogManager.d(TAG, "Main loaded, package=${param.packageName}")

        val classLoader = param.defaultClassLoader ?: return
        val gattClass = Reflect.findClass(GATT_SERVICE, classLoader)
        val adapterClass = Reflect.findClass(ADAPTER_SERVICE, classLoader)
        val scanControllerClass = Reflect.findClass(SCAN_CONTROLLER, classLoader)
        if (adapterClass == null || (gattClass == null && scanControllerClass == null)) {
            HookLogManager.e(TAG, "Bluetooth service classes not found")
            return
        }

        when {
            // Android 16+：扫描逻辑从 GattService 拆到独立的 ScanController
            scanControllerClass != null -> installScanController(scanControllerClass, adapterClass!!)
            gattClass == null -> HookLogManager.e(TAG, "GattService not found")
            hasMethod(gattClass, "start") -> installStartStop(gattClass, "start", "stop")
            hasMethod(gattClass, "initMiFeature") -> installStartStop(gattClass, "initMiFeature", "cleanup")
            else -> installConstructorStop(gattClass, adapterClass!!)
        }
    }

    private fun installStartStop(gattClass: Class<*>, start: String, stop: String) {
        runCatching {
            val startMethod = findMethod(gattClass, start) ?: error("$start not found")
            hook(startMethod).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { attachBroadcaster(it) }
                result
            }
            val stopMethod = findMethod(gattClass, stop) ?: error("$stop not found")
            hook(stopMethod).intercept { chain ->
                chain.thisObject?.let { detachBroadcaster(it) }
                chain.proceed()
            }
        }.onFailure { e ->
            HookLogManager.e(TAG, "hook start/stop failed: ${e.message}", e)
        }
    }

    private fun installConstructorStop(gattClass: Class<*>, adapterClass: Class<*>) {
        runCatching {
            val constructor = findConstructor(gattClass, adapterClass) ?: error("constructor not found")
            hook(constructor).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { attachBroadcaster(it) }
                result
            }
            val cleanupMethod = findMethod(gattClass, "cleanup") ?: error("cleanup not found")
            hook(cleanupMethod).intercept { chain ->
                chain.thisObject?.let { detachBroadcaster(it) }
                chain.proceed()
            }
        }.onFailure { e ->
            HookLogManager.e(TAG, "hook constructor/cleanup failed: ${e.message}", e)
        }
    }

    /**
     * Android 16+：扫描逻辑从 GattService 拆到独立的 ScanController（com.android.bluetooth.le_scan），
     * 由 AdapterService 在蓝牙开启时创建，其 onScanResult(...) 即原生扫描结果回调入口。
     */
    private fun installScanController(scanControllerClass: Class<*>, adapterClass: Class<*>) {
        runCatching {
            val constructors = findConstructors(scanControllerClass, adapterClass)
            if (constructors.isEmpty()) error("ScanController constructor not found")
            constructors.forEach { constructor ->
                hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { attachBroadcaster(it) }
                    result
                }
            }
            // 蓝牙关闭时 AdapterService.stopScanController() 会销毁扫描控制器，顺便停掉定时链
            findMethod(adapterClass, "stopScanController")?.let { stop ->
                hook(stop).intercept { chain ->
                    detachAll()
                    chain.proceed()
                }
            }
            HookLogManager.d(TAG, "hooked ScanController, constructors=${constructors.size}")
        }.onFailure { e ->
            HookLogManager.e(TAG, "hook ScanController failed: ${e.message}", e)
        }
    }

    /** 停止所有广播定时链（蓝牙扫描控制器销毁时调用）。 */
    private fun detachAll() {
        val drained = synchronized(attachments) {
            val values = attachments.values.toList()
            attachments.clear()
            values
        }
        drained.forEach { it.handler.removeCallbacks(it.runnable) }
    }

    private fun attachBroadcaster(gattService: Any) {
        synchronized(attachments) {
            // 同一个实例重复 start 时不重复挂载，避免每 500ms 注入多次。
            if (attachments.containsKey(gattService)) return
            val handler = Handler(Looper.getMainLooper())
            val broadcast = ScanBroadcaster(gattService, handler)
            attachments[gattService] = Attachment(handler, broadcast)
            handler.postDelayed(broadcast, INTERVAL_MS)
        }
    }

    private fun detachBroadcaster(gattService: Any) {
        val attachment = synchronized(attachments) { attachments.remove(gattService) } ?: return
        attachment.handler.removeCallbacks(attachment.runnable)
    }

    private class Attachment(
        val handler: Handler,
        val runnable: Runnable,
    )

    private class ScanBroadcaster(
        private val host: Any,
        private val handler: Handler,
    ) : Runnable {

        /** 首次成功解析后缓存，避免每 500ms 重复探测反射路径。 */
        private var scanPath: Pair<(Any) -> Any?, Boolean>? = null

        override fun run() {
            try {
                // 实时判断模式：仅当本机模拟(Self)时才注入；切换开关无需重启蓝牙，下一个 tick 即生效。
                // 配置由框架推送更新，读取无 IO 开销。
                if (HookConfig.isSimulateModeSelf()) {
                    if (scanPath == null) {
                        scanPath = resolveScanPath(host)
                    }
                    scanPath?.let { (getter, trailingMac) ->
                        val mac = HookConfig.getString(PrefKeys.PREF_MAC, DEFAULT_MAC)
                        val rssi = HookConfig.getString(PrefKeys.PREF_RSSI, DEFAULT_RSSI).toIntOrNull()
                            ?: DEFAULT_RSSI.toInt()
                        val advData = ByteUtils.hexStringToBytes(
                            HookConfig.getString(PrefKeys.PREF_DATA, DEFAULT_ADV_DATA),
                        )
                        getter(host)?.let { target ->
                            invokeScanResult(target, mac, rssi, advData, trailingMac)
                        }
                    }
                }
            } catch (e: Throwable) {
                // 任何异常都不允许中断 500ms 定时链，否则注入会永久停止。
                HookLogManager.e(TAG, "Mock scan injection failed: ${e.message}", e)
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    companion object {
        const val TAG = "BluetoothDebug"

        private const val TARGET_PACKAGE = "com.android.bluetooth"
        private const val GATT_SERVICE = "com.android.bluetooth.gatt.GattService"
        private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"
        private const val SCAN_CONTROLLER = "com.android.bluetooth.le_scan.ScanController"
        private const val INTERVAL_MS = 500L
        private const val DEFAULT_MAC = "76:A7:8A:67:66:C9"
        private const val DEFAULT_RSSI = "-50"
        private const val DEFAULT_ADV_DATA =
            "02010403033CFE17FF0001B500024271A7B6000000C983926CB1011000000000000000000000000000000000000000000000000000000000000000000000"

        private fun hasMethod(clazz: Class<*>, name: String): Boolean =
            clazz.declaredMethods.any { it.name == name }

        /** 按名字取方法：优先零参；仅有唯一重载时退回该重载。 */
        private fun findMethod(clazz: Class<*>, name: String): Method? {
            val candidates = clazz.declaredMethods.filter { it.name == name }
            return candidates.firstOrNull { it.parameterCount == 0 } ?: candidates.singleOrNull()
        }

        /** 取所有第一个参数兼容 [firstParam] 的构造器，按参数个数升序（优先最短重载）。 */
        private fun findConstructors(clazz: Class<*>, firstParam: Class<*>): List<Constructor<*>> =
            clazz.declaredConstructors
                .filter { it.parameterCount > 0 && it.parameterTypes[0].isAssignableFrom(firstParam) }
                .sortedBy { it.parameterCount }

        private fun findConstructor(clazz: Class<*>, firstParam: Class<*>): Constructor<*>? =
            findConstructors(clazz, firstParam).firstOrNull()
                ?: clazz.declaredConstructors.firstOrNull { it.parameterCount == 1 }

        private fun resolveScanPath(host: Any): Pair<(Any) -> Any?, Boolean>? {
            val hostClass = host.javaClass
            val candidates: List<Pair<String, (Any) -> Any?>> = buildList {
                // Android 16+ 传入的就是 ScanController 实例本身
                add("self" to { s: Any -> s })
                if (hasMethod(hostClass, "getScanController")) {
                    add("getScanController" to { s: Any -> Reflect.callMethod(s, "getScanController") })
                }
                if (hasMethod(hostClass, "getTransitionalScanHelper")) {
                    add(
                        "getTransitionalScanHelper" to { s: Any ->
                            Reflect.callMethod(s, "getTransitionalScanHelper")
                        },
                    )
                }
            }

            for ((name, getter) in candidates) {
                val target = runCatching { getter(host) }.getOrNull() ?: continue
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
            Reflect.callMethod(target, "onScanResult", *args)
        }
    }
}
