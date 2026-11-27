# VYRO Production R8 / ProGuard Keep Rules

# Preserve stack trace line numbers & source mapping without leaking paths
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve JNI native bridge methods and callbacks
-keepclasseswithmembernames class com.vyro.bridge.jni.** {
    native <methods>;
}
-keep class com.vyro.bridge.jni.NativeEngine {
    *;
}

# Preserve kotlinx.serialization models & serializer companion factories
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.vyro.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.vyro.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Preserve custom timeline Android View constructors referenced by AndroidView/Compose
-keepclassmembers class com.vyro.feature.editor.TimelineSurfaceView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Preserve BuildConfig flags
-keep class com.example.BuildConfig { *; }
