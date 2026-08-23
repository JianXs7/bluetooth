package net.ankio.bluetooth.utils

/** SharedPreferences `config` 键名，读写统一走 [SpUtils] */
object PrefKeys {
    const val PREF_MAC = "pref_mac"
    const val PREF_DATA = "pref_data"
    const val PREF_RSSI = "pref_rssi"
    const val PREF_COMPANY = "pref_company"

    const val WEBDAV_MODE = "webdav_mode"
    const val SIMULATE_MODE = "simulate_mode"
    const val WEBDAV_LAST = "webdav_last"
    const val SETTING_LANGUAGE = "setting_language"

    /** 是否在桌面显示应用图标（控制 MainActivityLauncher 的启用/禁用） */
    const val SHOW_LAUNCHER_ICON = "show_launcher_icon"
}
