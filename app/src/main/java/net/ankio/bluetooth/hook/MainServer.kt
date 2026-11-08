package net.ankio.bluetooth.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import net.ankio.bluetooth.BuildConfig
import net.ankio.bluetooth.utils.HookLogManager

/**
 * Xposed 入口（libxposed 新版 API）：仅处理 app 自身进程 `net.ankio.bluetooth`。
 *
 * 对应 Shadow 1.0.9 的 MainServer。它 Hook 应用内 HookUtils 的几个静态方法，
 * 使 UI 能正确判断「模块已激活」以及模块/框架版本，用于首页状态条展示。
 *
 * 与 Main 一样由 META-INF/xposed/java_init.list 声明为入口，两个入口各自校验目标包名。
 */
class MainServer : XposedModule() {

    private val tag = "BluetoothDebug"

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != BuildConfig.APPLICATION_ID) return
        XposedApi.attach(this)
        HookLogManager.d(tag, "MainServer hook self: ${param.packageName}")

        val classLoader = param.defaultClassLoader ?: return
        val hookUtils = Reflect.findClass("net.ankio.bluetooth.utils.HookUtils", classLoader) ?: return

        runCatching {
            hookUtils.declaredMethods.firstOrNull { it.name == "getActiveAndSupportFramework" }?.let {
                hook(it).intercept { true }
            }
            hookUtils.declaredMethods.firstOrNull { it.name == "getAppVersion" }?.let {
                hook(it).intercept { BuildConfig.VERSION_CODE }
            }
            hookUtils.declaredMethods.firstOrNull { it.name == "getXposedVersion" }?.let {
                hook(it).intercept { getApiVersion() }
            }
        }.onFailure { e ->
            HookLogManager.e(tag, "MainServer hook failed: ${e.message}", e)
        }
    }
}
