# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# root 桥接：AIDL 接口与 RootService 由 libsu 按类名反射启动，不能被混淆或裁剪
-keep class com.example.smartalarm.root.** { *; }

# Preserve the narrow controller/data contract for testing the actual signed, minified APK.
# These are in-process APIs only, not exported Android components or production test intents.
-keep class com.example.smartalarm.bridge.AlarmSetController { *; }
-keep class com.example.smartalarm.bridge.AlarmSetController$* { *; }
-keep class com.example.smartalarm.bridge.RootBackend { *; }
-keep class com.example.smartalarm.bridge.SetUiState* { *; }
-keep class com.example.smartalarm.bridge.SetUiState$* { *; }
-keep class com.example.smartalarm.bridge.StandardClockResolver* { *; }
-keep class com.example.smartalarm.bridge.ClockChoice { *; }
-keep class com.example.smartalarm.core.interaction.** { *; }
-keep class com.example.smartalarm.data.** { *; }

# AndroidJUnitRunner shares this runtime dependency with the target APK. Without this
# rule the app optimizer inlines it, while the separate test APK still needs the class.
-keep class androidx.tracing.** { *; }
# Instrumentation and the target share these public runtime APIs. Keeping the
# shared contract avoids pruning/inlining facades needed only by the test APK.
-keep class kotlin.** { *; }
-keep class androidx.lifecycle.Lifecycle** { *; }
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep interface com.google.common.util.concurrent.ListenableFuture { *; }
