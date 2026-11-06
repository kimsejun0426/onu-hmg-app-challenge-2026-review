# Test APKs call the target app by original names. These are not market rules.
-keep,allowoptimization class dev.carplaymate.demo.** { *; }
-keep,allowoptimization interface dev.carplaymate.demo.** { *; }
-keep class androidx.tracing.** { *; }
