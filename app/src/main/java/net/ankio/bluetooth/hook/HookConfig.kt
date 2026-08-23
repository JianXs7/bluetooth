package net.ankio.bluetooth.hook

import de.robv.android.xposed.XSharedPreferences
import net.ankio.bluetooth.model.SimulateMode
import net.ankio.bluetooth.utils.HookLogManager
import net.ankio.bluetooth.utils.PrefKeys

object HookConfig {

    private const val PREF_PACKAGE = "net.ankio.bluetooth"
    private const val PREF_NAME = "config"

    private val pref = XSharedPreferences(PREF_PACKAGE, PREF_NAME)

    fun reload() {
        if (pref.hasFileChanged()) {
            pref.reload()
            HookLogManager.reloadConfig()
        }
    }

    fun getString(key: String, def: String): String {
        reload()
        return pref.getString(key, def) ?: def
    }

    fun getBoolean(key: String, def: Boolean): Boolean {
        reload()
        return pref.getBoolean(key, def)
    }

    /**
     * 当前是否为「本机模拟」模式（[SimulateMode.Self]）。
     *
     * 内部通过 [reload] 惰性刷新（仅当 XSharedPreferences 底层文件变化时才真正 reload），
     * 因此在广播高频调用下仍保持低开销；该语义清晰、避免在调用处拼接枚举字符串。
     */
    fun isSimulateModeSelf(): Boolean =
        getString(PrefKeys.SIMULATE_MODE, "") == SimulateMode.Self.toString()
}
