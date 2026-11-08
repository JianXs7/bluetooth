package net.ankio.bluetooth.hook

import net.ankio.bluetooth.model.SimulateMode
import net.ankio.bluetooth.utils.PrefKeys

/**
 * 模块配置读取。
 *
 * 旧实现通过 XSharedPreferences 读取宿主 App 的「世界可读」配置文件，
 * 该机制已被 LSPosed 标记为废弃（计划于 2.3.0 移除）。这里改用 libxposed 的远程配置接口
 * [io.github.libxposed.api.XposedInterface.getRemotePreferences]：只读快照，由框架在配置变化后
 * 主动推送，因此无需（也无法）手动 reload。
 */
object HookConfig {

    fun getString(key: String, def: String): String =
        runCatching { XposedApi.remoteConfig?.getString(key, def) }.getOrNull() ?: def

    fun getBoolean(key: String, def: Boolean): Boolean =
        runCatching { XposedApi.remoteConfig?.getBoolean(key, def) }.getOrNull() ?: def

    /**
     * 当前是否为「本机模拟」模式（[SimulateMode.Self]）。
     *
     * 该语义清晰、避免在调用处拼接枚举字符串。
     */
    fun isSimulateModeSelf(): Boolean =
        getString(PrefKeys.SIMULATE_MODE, "") == SimulateMode.Self.toString()
}
