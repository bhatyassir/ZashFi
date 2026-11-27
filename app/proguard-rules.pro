# Add project specific ProGuard rules here.

# Keep source file and line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization-json
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.shaazifi.app.**$$serializer { *; }
-keepclassmembers class com.shaazifi.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.shaazifi.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.shaazifi.app.** { *; }

# OkHttp, Okio, and optional Conscrypt/TLS platform providers
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keepclassmembers class * extends okhttp3.WebSocketListener { *; }

# WorkManager workers instantiated via reflection
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.WorkerParameters

# ZXing QR code encoder
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Strip verbose and debug logs in release/R8 builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
