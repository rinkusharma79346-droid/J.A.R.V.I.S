# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts
-keepattributes *Annotation*
-keep class com.vayu.agent.ActionModels.** { *; }
-keep class com.vayu.agent.AgentAction.** { *; }
