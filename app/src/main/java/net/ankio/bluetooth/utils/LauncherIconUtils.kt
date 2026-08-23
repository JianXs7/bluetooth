package net.ankio.bluetooth.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 控制是否在桌面显示应用图标。
 *
 * 通过 PackageManager 动态启用/禁用 manifest 中的 `.MainActivityLauncher` 组件
 * （它携带 [android.intent.category.LAUNCHER]）。禁用后桌面不再显示图标，
 * 模块仍可通过 LSPosed 管理器或其它入口打开；启用后恢复图标。
 */
object LauncherIconUtils {

    /** `.MainActivityLauncher`（activity-alias）的完整类名 */
    private const val LAUNCHER_ALIAS = "net.ankio.bluetooth.MainActivityLauncher"

    /**
     * 应用当前保存的「是否显示图标」设置。
     *
     * @param context 任意 Context
     */
    fun apply(context: Context) {
        val show = SpUtils.getBoolean(PrefKeys.SHOW_LAUNCHER_ICON, true)
        setEnabled(context, show)
    }

    /**
     * 显示或隐藏桌面图标。
     *
     * @param context 任意 Context
     * @param enabled true=显示图标（启用组件）；false=隐藏图标（禁用组件）
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            val component = ComponentName(context.packageName, LAUNCHER_ALIAS)
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
        } catch (e: Throwable) {
            // 忽略：设置失败不影响主流程（图标可能仍在，但功能不受影响）
            android.util.Log.w("BluetoothDebug", "apply launcher icon failed", e)
        }
    }
}
