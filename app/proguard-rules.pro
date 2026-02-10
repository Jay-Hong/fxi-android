# FXi release minify notes:
# - Keep rules should be minimal and targeted.
# - Most libraries (Firebase/OkHttp/Retrofit/RevenueCat/etc.) ship consumer ProGuard rules.
# - Overly broad `-keep` rules reduce shrinking/obfuscation and can increase binary size.

# --- Attributes needed for reflection/annotations (Retrofit, DI metadata, etc.) ---
-keepattributes *Annotation*
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# --- Kotlinx Serialization (app models) ---
# The generated `$$serializer` classes must remain.
-keep,includedescriptorclasses class com.jay.fxi.**$$serializer { *; }

# Some serializers are referenced via `serializer(...)` and companions.
-keepclassmembers class com.jay.fxi.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Crashlytics: keep source/line info for readable stack traces ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
