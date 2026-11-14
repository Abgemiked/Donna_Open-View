# DONNA-C7: ProGuard/R8 Rules für React Native + Donna-Module

# React Native — Core
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }

# Donna App-Klassen
-keep class de.abgemiked.donna.** { *; }

# React Native New Architecture (JSI / Fabric)
-keep class com.facebook.react.turbomodule.** { *; }
-keep class com.facebook.react.fabric.** { *; }

# AndroidX Security / EncryptedSharedPreferences (DONNA-103)
-keep class androidx.security.crypto.** { *; }

# AndroidX Biometric (DONNA-103)
-keep class androidx.biometric.** { *; }

# Health Connect (DONNA-120)
-keep class androidx.health.connect.** { *; }

# Google Play Services Location + Activity Recognition (DONNA-119/121)
-keep class com.google.android.gms.location.** { *; }

# Google AI Edge / AICore (DONNA-157)
-keep class com.google.ai.edge.** { *; }

# Apache Commons Compress (DONNA-192)
-keep class org.apache.commons.compress.** { *; }
# commons-compress optionale Abhängigkeiten — nicht im Classpath, aber von R8 referenziert.
# Wir nutzen nur BZip2/Tar — diese Kompressions-Libs werden nie aufgerufen.
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn org.objectweb.asm.AnnotationVisitor
-dontwarn org.objectweb.asm.Attribute
-dontwarn org.objectweb.asm.ClassReader
-dontwarn org.objectweb.asm.ClassVisitor
-dontwarn org.objectweb.asm.FieldVisitor
-dontwarn org.objectweb.asm.Label
-dontwarn org.objectweb.asm.MethodVisitor
-dontwarn org.objectweb.asm.Type

# sherpa-onnx native library (DONNA-192)
-keep class com.k2fsa.sherpa.** { *; }

# ONNX Runtime GenAI (DONNA-189)
-keep class ai.onnxruntime.** { *; }
-keep class microsoft.onnxruntime.** { *; }

# Notifee (DONNA-13)
-keep class io.invertase.notifee.** { *; }

# React Native Voice (DONNA-73)
-keep class com.wenkesj.voice.** { *; }

# Suppress notes for native methods
-dontnote com.facebook.**
-dontnote com.google.**

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enums intact
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
