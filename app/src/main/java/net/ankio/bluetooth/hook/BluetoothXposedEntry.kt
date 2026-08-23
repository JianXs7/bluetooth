package net.ankio.bluetooth.hook

import net.ankio.bluetooth.BuildConfig
import net.ankio.bluetooth.utils.HookLogManager
import net.ankio.xposed.lib.hook.App
import net.ankio.xposed.lib.hook.api.HookerManifest
import net.ankio.xposed.lib.log.Logger

class BluetoothXposedEntry : App() {

    override val hookers: List<HookerManifest> = listOf(
        // 蓝牙进程的本机模拟注入改由传统入口 BtHookEntry 负责（见 assets/xposed_init）。
        // 这里仅保留对 app 自身进程的 Hook（SelfHooker），避免框架入口在蓝牙进程注入受限。
        SelfHooker(),
    )

    override val debug: Boolean = BuildConfig.DEBUG

    override val showLoadSuccessToast: Boolean = false

    override val loadSuccessMessage: String = ""

    override val hostApplicationId: String = BuildConfig.APPLICATION_ID

    override fun setUpLogger() {
        Logger.install(object : Logger {
            override fun d(msg: String) = HookLogManager.d(msg)

            override fun i(msg: String) = HookLogManager.d(msg)

            override fun w(msg: String) = HookLogManager.e(msg)

            override fun e(msg: String, tr: Throwable?) {
                if (tr != null) {
                    HookLogManager.e(msg, tr)
                } else {
                    HookLogManager.e(msg)
                }
            }
        })
    }
}
