# Door Monitor ProGuard / R8 rules

# --- HiveMQ MQTT client uses Netty + reflection ---
-keep class com.hivemq.client.** { *; }
-dontwarn com.hivemq.client.**
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-dontwarn reactor.**
-dontwarn org.reactivestreams.**

# --- NanoHTTPD ---
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# --- libVLC (native bindings via JNI) ---
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# --- Media3 ---
-dontwarn androidx.media3.**

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.doormonitor.** {
    *** Companion;
}
-keepclasseswithmembers class com.doormonitor.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep our broadcast receivers / services referenced from the manifest.
-keep class com.doormonitor.boot.BootReceiver { *; }
-keep class com.doormonitor.service.KioskForegroundService { *; }
-keep class com.doormonitor.kiosk.DeviceAdminReceiver { *; }
