# ═══════════════════════════════════════════════════════════════
# V.A.Y.U Agent — ProGuard Rules for Android 10+ Compatibility
# ═══════════════════════════════════════════════════════════════

# ─── Keep Kotlin metadata ───
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions

# ─── Keep all Vayu model/data classes (used by Gson) ───
-keep class com.vayu.agent.AgentAction { *; }
-keep class com.vayu.agent.SequenceAction { *; }
-keep class com.vayu.agent.AgentRequest { *; }
-keep class com.vayu.agent.HistoryEntry { *; }
-keep class com.vayu.agent.ValidationResult { *; }
-keep class com.vayu.agent.ProviderConfig { *; }
-keep class com.vayu.agent.McpCommandState { *; }

# ─── Keep custom view (referenced in layout XML by class name) ───
-keep class com.vayu.agent.VayuView { *; }
-keepclassmembers class com.vayu.agent.VayuView { *; }

# ─── Keep all Services (referenced in AndroidManifest by class name) ───
-keep class com.vayu.agent.VayuService { *; }
-keep class com.vayu.agent.HUDService { *; }
-keep class com.vayu.agent.VayuApp { *; }
-keep class com.vayu.agent.MainActivity { *; }
-keep class com.vayu.agent.SettingsActivity { *; }

# ─── Keep API provider classes (created via reflection-like factory) ───
-keep class com.vayu.agent.ApiProvider { *; }
-keep class com.vayu.agent.GeminiProvider { *; }
-keep class com.vayu.agent.OpenAiProvider { *; }
-keep class com.vayu.agent.NvidiaProvider { *; }
-keep class com.vayu.agent.ProviderFactory { *; }

# ─── Keep other app classes ───
-keep class com.vayu.agent.SettingsManager { *; }
-keep class com.vayu.agent.RelayClient { *; }
-keep class com.vayu.agent.AgentMemory { *; }

# ─── Keep AccessibilityService subclasses ───
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ─── Gson rules ───
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# ─── OkHttp rules ───
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okhttp3.** { *; }

# ─── Kotlin stdlib ───
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }

# ─── Kotlin coroutines ───
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ─── Keep enum values ───
-keepclassmembers enum * {
    *;
}

# ─── Keep classes with @JvmStatic ───
-keepclassmembers class * {
    @kotlin.jvm.JvmStatic <methods>;
}

# ─── AndroidX / Material ───
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# ─── Keep R classes ───
-keep class **.R$* { *; }

# ─── Disable aggressive optimizations that can break reflection ───
-optimizations !class/unboxing/enum,!code/simplification/arithmetic,!code/simplification/cast,!field/propagation/value,!method/propagation/returnvalue

# ─── Keep all classes with Gson @SerializedName annotations ───
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
