package net.ankio.bluetooth.viewmodel

import androidx.lifecycle.ViewModel
import net.ankio.bluetooth.App
import net.ankio.bluetooth.utils.LauncherIconUtils
import net.ankio.bluetooth.utils.LocaleDelegate
import net.ankio.bluetooth.utils.PrefKeys
import net.ankio.bluetooth.utils.SpUtils
import net.ankio.bluetooth.utils.persistedState

/**
 * 设置页状态：负责语言配置的读取、持久化与运行时 Locale 切换。
 */
class SettingsViewModel : ViewModel() {
    /**
     * 当前语言标记（如 SYSTEM / zh-CN / en-US）。
     * 赋值后会立即落盘，并在语言真正变化时递增语言版本。
     */
    var languageTag: String by persistedState(
        initialValue = SpUtils.getString(PrefKeys.SETTING_LANGUAGE, "SYSTEM").ifEmpty { "SYSTEM" },
        debounceMs = 0L,
    ) { value ->
        SpUtils.putString(PrefKeys.SETTING_LANGUAGE, value)
        LocaleDelegate.updateDefaultLocale(App.getLocale(value))
    }

    /**
     * 是否在桌面显示应用图标。
     * 切换后立即落盘并启用/禁用 MainActivityLauncher（无需重启）。
     */
    var showLauncherIcon: Boolean by persistedState(
        initialValue = SpUtils.getBoolean(PrefKeys.SHOW_LAUNCHER_ICON, true),
        debounceMs = 0L,
    ) { value ->
        SpUtils.putBoolean(PrefKeys.SHOW_LAUNCHER_ICON, value)
        LauncherIconUtils.setEnabled(App.context, value)
    }
}
