package net.ankio.bluetooth.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 当前进程内已注入的模块实例（libxposed 新版 API）。
 *
 * 新版 API 不再提供 XSharedPreferences、XposedBridge.log 这类全局静态入口，
 * 读取宿主配置与输出日志都必须经由模块实例完成，因此在此持有引用，
 * 供 [HookConfig]、[net.ankio.bluetooth.utils.HookLogManager] 等工具类使用。
 */
internal object XposedApi {

    private const val TAG = "BluetoothDebug"

    /** 配置分组名，对应宿主 App 中的 SharedPreferences("config")。 */
    const val CONFIG_GROUP = "config"

    @Volatile
    var module: XposedInterface? = null
        private set

    /** 跨进程只读配置：由框架推送更新，无需（也无法）手动 reload。 */
    @Volatile
    var remoteConfig: SharedPreferences? = null
        private set

    fun attach(instance: XposedInterface) {
        module = instance
        remoteConfig = runCatching { instance.getRemotePreferences(CONFIG_GROUP) }
            .onFailure {
                instance.log(Log.ERROR, TAG, "getRemotePreferences failed: ${it.message}")
            }
            .getOrNull()
        instance.log(
            Log.DEBUG,
            TAG,
            "api=${instance.apiVersion}, remote prefs entries=${remoteConfig?.all?.size ?: -1}",
        )
    }
}
