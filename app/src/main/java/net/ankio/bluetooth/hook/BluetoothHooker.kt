package net.ankio.bluetooth.hook

import net.ankio.bluetooth.hook.GattServiceHooker
import net.ankio.xposed.lib.dex.model.Clazz
import net.ankio.xposed.lib.hook.api.HookerManifest
import net.ankio.xposed.lib.hook.api.PartHooker

class BluetoothHooker : HookerManifest() {

    override val packageName: String = "com.android.bluetooth"

    override val appName: String = "Bluetooth Service"

    override val systemApp: Boolean = true

    // 系统蓝牙进程是系统服务进程，其 Application（BluetoothApplication）的创建时机很早，
    // 且可能不与框架预期的 `Instrumentation.callApplicationOnCreate` 路径同步。
    // 留空 applicationName 让框架走「直接取当前 Application」路径（AndroidAppHelper.currentApplication），
    // 不阻塞等待回调，从而能尽快执行 startHooker -> GattServiceHooker。
    override var applicationName: String = ""

    override fun hookLoadPackage() {
        HookConfig.reload()
    }

    override var partHookers: MutableList<PartHooker> = mutableListOf(
        GattServiceHooker(),
    )

    override var rules: MutableList<Clazz> = mutableListOf()
}
