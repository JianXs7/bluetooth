package net.ankio.bluetooth.utils

import android.util.Log
import net.ankio.bluetooth.hook.HookConfig
import net.ankio.bluetooth.hook.XposedApi

/**
 * Xposed Hook日志管理工具类
 * 提供可控制的日志输出功能，支持通过设置开关控制日志输出
 *
 * 日志最终经 libxposed 的 XposedInterface.log 输出（可在 LSPosed 日志页查看），
 * 开关取自宿主 App 的配置项 [LOG_ENABLED_KEY]，由框架推送更新。
 *
 * @author SmallBaby
 * @date 2025-11-13
 */
object HookLogManager {

    private const val LOG_ENABLED_KEY = "pref_hook_log_enabled"

    /**
     * 默认的日志标签
     */
    private const val DEFAULT_TAG = "BluetoothDebug"

    /**
     * 检查是否启用日志输出
     * @return true 如果启用日志输出，false 如果禁用
     */
    private fun isLogEnabled(): Boolean = HookConfig.getBoolean(LOG_ENABLED_KEY, false) // 默认关闭日志

    /**
     * 输出调试日志
     * @param message 日志消息
     */
    fun d(message: String) {
        log(DEFAULT_TAG, message)
    }

    /**
     * 输出带标签的调试日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun d(tag: String, message: String) {
        log(tag, message)
    }

    /**
     * 输出错误日志
     * @param message 日志消息
     */
    fun e(message: String) {
        log(DEFAULT_TAG, message, isError = true)
    }

    /**
     * 输出带标签的错误日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun e(tag: String, message: String) {
        log(tag, message, isError = true)
    }

    /**
     * 输出异常日志
     * @param message 日志消息
     * @param throwable 异常对象
     */
    fun e(message: String, throwable: Throwable) {
        log(DEFAULT_TAG, "$message: ${describe(throwable)}", isError = true)
    }

    /**
     * 输出带标签的异常日志
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常对象
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        log(tag, "$message: ${describe(throwable)}", isError = true)
    }

    /**
     * 生成异常描述：类型 + 消息 + 精简堆栈。
     * 直接在 LSPosed 日志里定位问题，无需额外抓 logcat。
     */
    private fun describe(throwable: Throwable): String {
        val stack = throwable.stackTrace.take(6).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        return "${throwable.javaClass.simpleName}(${throwable.message}) $stack"
    }

    /**
     * 核心日志输出方法
     * @param tag 日志标签
     * @param message 日志消息
     * @param isError 是否为错误日志
     */
    private fun log(tag: String, message: String, isError: Boolean = false) {
        // 只有启用日志时才输出
        if (!isLogEnabled()) return
        try {
            val module = XposedApi.module ?: return
            val formattedMessage = if (isError) {
                "[ERROR] $tag : $message"
            } else {
                "[DEBUG] $tag : $message"
            }
            module.log(if (isError) Log.ERROR else Log.DEBUG, DEFAULT_TAG, formattedMessage)
        } catch (e: Exception) {
            // 日志输出失败时不影响主功能
        }
    }

    /**
     * 检查日志开关状态（用于设置界面显示）
     * @return 当前日志开关状态
     */
    fun getLogStatus(): Boolean = isLogEnabled()
}
