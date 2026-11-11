package net.ankio.bluetooth.utils

import android.util.Log
import androidx.core.content.edit
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 与 Xposed 框架之间的通信通道。
 *
 * 新版 libxposed API 不再把模块注入到模块自身进程（模块 App 既不需要、
 * 也不应该把作用域勾选成自己），因此配置不能再用 XSharedPreferences 跨进程读取。
 *
 * 模块 App 启动后，框架会向 `<包名>.XposedService` 这个 ContentProvider 下发
 * 一个 [XposedService]；我们通过它把本地配置写入框架的远程配置
 * （Remote Preferences，实际存放在 LSPosed 数据库里），蓝牙进程里的
 * Hook 代码再用 XposedInterface.getRemotePreferences 读到同一份数据。
 */
object ModuleService : XposedServiceHelper.OnServiceListener {

    private const val TAG = "BluetoothDebug"

    /** 远程配置分组名，必须与 hook 侧 XposedApi.CONFIG_GROUP 保持一致 */
    const val CONFIG_GROUP = "config"

    @Volatile
    private var service: XposedService? = null

    /** 框架服务是否已连接，等价于「模块已激活」 */
    val isBound: Boolean
        get() = service != null

    /** 框架 API 版本，未连接框架时为 0 */
    val apiVersion: Int
        get() = runCatching { service?.apiVersion ?: 0 }.getOrDefault(0)

    /** 框架名称（例如 LSPosed），未连接框架时为空串 */
    val frameworkName: String
        get() = runCatching { service?.frameworkName.orEmpty() }.getOrDefault("")

    /** 注册服务监听，必须在 Application 启动时调用一次 */
    fun register() {
        runCatching { XposedServiceHelper.registerListener(this) }
            .onFailure { Log.e(TAG, "register xposed service listener failed", it) }
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        Log.i(TAG, "xposed service bound: $frameworkName, api=$apiVersion")
        syncConfig()
    }

    override fun onServiceDied(service: XposedService) {
        this.service = null
        Log.w(TAG, "xposed service died")
    }

    /**
     * 把本地配置整体同步到框架的远程配置，供蓝牙进程读取。
     * 未连接框架时什么也不做。
     */
    fun syncConfig() {
        val service = service ?: return
        runCatching {
            val local = SpUtils.all()
            val remote = service.getRemotePreferences(CONFIG_GROUP)
            remote.edit {
                clear()
                local.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> Unit
                    }
                }
            }
            Log.d(TAG, "synced ${local.size} config entries to xposed remote prefs")
        }.onFailure { Log.e(TAG, "sync config to xposed remote prefs failed", it) }
    }
}
