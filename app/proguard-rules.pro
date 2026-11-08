# Release: R8 shrinking + obfuscation for app + Xposed module.

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- Xposed module entry & hook surface ---
# META-INF/xposed/java_init.list 以「类名字符串」引用入口类，必须保留名称。
-keep class net.ankio.bluetooth.hook.** { *; }

# SelfHooker resolves these by name at runtime.
-keep class net.ankio.bluetooth.utils.HookUtils { *; }

# libxposed API 由框架在运行时提供（compileOnly），无需打包也无需告警。
-dontwarn io.github.libxposed.**

# --- Gson (WebDAV payload + company_ids.json) ---
-keep class net.ankio.bluetooth.ble.BluetoothData { *; }
-keep class net.ankio.bluetooth.data.BluetoothCompany { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# --- Android components declared in manifest ---
-keep class net.ankio.bluetooth.App { *; }
-keep class net.ankio.bluetooth.ui.MainActivity { *; }
-keep class net.ankio.bluetooth.service.** extends android.app.Service { *; }

# --- Foreground services & notifications ---
-keepclassmembers class * extends android.app.Service {
    public <init>();
}

# --- Kotlin ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# --- Compose (library consumer rules apply; silence optional) ---
-dontwarn androidx.compose.**

# --- Third-party libraries ---
-keep class net.ankio.webdav.lib.** { *; }
-keep class net.ankio.theme.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Generated ---
-keep class net.ankio.bluetooth.BuildConfig { *; }
-keep class net.ankio.utils.LangList { *; }
