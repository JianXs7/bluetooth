package net.ankio.bluetooth.hook

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 反射辅助方法。
 *
 * 旧版依赖 XposedHelpers 提供的 findClass/callMethod，新版 API 不再内置这些工具，
 * 这里只实现本模块用到的少量能力，避免额外引入整套 legacy helper。
 */
internal object Reflect {

    fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    /** 读取实例字段（含父类），失败返回 null。 */
    fun getField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            runCatching { current!!.getDeclaredField(name) }.getOrNull()?.let { field ->
                field.isAccessible = true
                return runCatching { field.get(instance) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    /**
     * 调用实例方法，支持基本类型参数的自动装箱/转换（对齐 XposedHelpers.callMethod 的行为）。
     *
     * 目标方法内部抛出的异常会解包成原始异常，便于日志定位真正原因。
     */
    fun callMethod(instance: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(instance.javaClass, name, args)
            ?: throw NoSuchMethodException("$name with ${args.size} arg(s) in ${instance.javaClass.name}")
        method.isAccessible = true
        return try {
            method.invoke(instance, *coerce(method.parameterTypes, args))
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun findMethod(clazz: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == args.size && isApplicable(it.parameterTypes, args)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun isApplicable(types: Array<Class<*>>, args: Array<out Any?>): Boolean =
        types.indices.all { i ->
            val type = types[i]
            val arg = args[i]
            when {
                arg == null -> !type.isPrimitive
                type.isInstance(arg) -> true
                // 基本类型参数允许由装箱值转换
                type.isPrimitive -> arg is Number || arg is Boolean || arg is Char
                else -> false
            }
        }

    private fun coerce(types: Array<Class<*>>, args: Array<out Any?>): Array<Any?> =
        Array(types.size) { i ->
            val type = types[i]
            val arg = args[i]
            when {
                arg == null -> null
                type.isInstance(arg) -> arg
                type == Int::class.javaPrimitiveType -> (arg as Number).toInt()
                type == Long::class.javaPrimitiveType -> (arg as Number).toLong()
                type == Short::class.javaPrimitiveType -> (arg as Number).toShort()
                type == Byte::class.javaPrimitiveType -> (arg as Number).toByte()
                type == Double::class.javaPrimitiveType -> (arg as Number).toDouble()
                type == Float::class.javaPrimitiveType -> (arg as Number).toFloat()
                else -> arg
            }
        }
}
