# HomeAlertAndroid ProGuard / R8 Optimization & Obfuscation Rules

-allowaccessmodification
-repackageclasses 'o'

# Preserve line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip verbose/debug logging in release APK
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# CameraX & Camera2 internals
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { *; }
-keep class androidx.camera.core.** { *; }
-keepclassmembers class * implements androidx.camera.core.impl.Config$Option { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Jakarta Mail / Android Mail (SMTP TLS)
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class org.apache.harmony.awt.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.**
-dontwarn java.lang.management.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Network-bound SMTP socket factory loaded via Class.forName by Jakarta Mail
-keep class com.homealert.android.network.GmailSmtpAlertSender$NetworkBoundSocketFactory { *; }

# Application data models & JSON serialization
-keep class com.homealert.android.data.model.** { *; }
