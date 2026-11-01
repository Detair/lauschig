# Lauschig release rules. Hilt, Room, Media3, WorkManager, Coil and Compose ship consumer rules.

# --- kotlinx.serialization (abs-api models, navigation routes, DataStore account) -------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.wolftown.lauschig.**$$serializer { *; }
-keepclassmembers class io.wolftown.lauschig.** {
    *** Companion;
}

# --- Retrofit (suspend functions + generic return types of the abs-api interfaces) ------------
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface io.wolftown.lauschig.absapi.** { *; }
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# --- OkHttp / Okio ----------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Tink (token encryption) ------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.annotations.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.proto.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# --- Room (generated *_Impl classes are referenced reflectively) -------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Readable crash stack traces from the closed test.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Type-safe navigation routes are resolved via their serializers; keep the route classes intact.
-keep class io.wolftown.lauschig.ui.nav.** { *; }
