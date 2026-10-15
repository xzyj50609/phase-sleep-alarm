# AndroidX Test's optional static-analysis annotations are not runtime classes.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
# Instrumentation class filters are names supplied by the runner/host scripts.
-keep class com.example.smartalarm.**Test { *; }
