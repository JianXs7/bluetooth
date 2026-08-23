package net.ankio.bluetooth.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import net.ankio.bluetooth.BuildConfig
import net.ankio.bluetooth.utils.HookLogManager

/**
 * 传统 Xposed 入口：仅处理 app 自身进程 `net.ankio.bluetooth`。
 *
 * 对应 Shadow 1.0.9 的 MainServer。它 Hook 应用内 HookUtils 的几个静态方法，
 * 使 UI 能正确判断「模块已激活」以及模块/框架版本，用于首页状态条展示。
 *
 * 之所以用纯传统入口而非 net.ankio.xposed.lib 框架：在该型 ColorOS 16 金标设备上，
 * 框架入口不被 LSPosed 注入蓝牙进程；纯传统入口（IXposedHookLoadPackage）则正常。
 */
class MainServer : IXposedHookLoadPackage {

    private val tag = "BluetoothDebug"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null || lpparam.packageName != BuildConfig.APPLICATION_ID) return
        HookLogManager.d(tag, "MainServer hook self: ${lpparam.packageName}")
        val cClass = XposedHelpers.findClass("net.ankio.bluetooth.utils.HookUtils", lpparam.classLoader)

        XposedHelpers.findAndHookMethod(
            cClass,
            "getActiveAndSupportFramework",
            XC_MethodReplacement.returnConstant(true),
        )
        XposedHelpers.findAndHookMethod(
            cClass,
            "getAppVersion",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = BuildConfig.VERSION_CODE
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            cClass,
            "getXposedVersion",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = XposedBridge.getXposedVersion()
                }
            },
        )
    }
}
