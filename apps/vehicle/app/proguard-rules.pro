# Pleos speech 3.3.1: confirmed public void (String, String) logging methods.
# These SDK-only wrappers log request/result payloads. Never disable all app logging.
-assumenosideeffects class ai.pleos.playground.stt.utils.Log {
    public void a(java.lang.String, java.lang.String);
    public void b(java.lang.String, java.lang.String);
    public void c(java.lang.String, java.lang.String);
    public void d(java.lang.String, java.lang.String);
}
-assumenosideeffects class ai.pleos.playground.tts.utils.Log {
    public void a(java.lang.String, java.lang.String);
    public void b(java.lang.String, java.lang.String);
    public void c(java.lang.String, java.lang.String);
    public void d(java.lang.String, java.lang.String);
}

# AndroidViewModelFactory reflectively calls this constructor. Other app classes
# may shrink in release; instrumentation-only binary seams are kept in demo/debug.
-keepclassmembers class dev.carplaymate.demo.DeveloperViewModel {
    public <init>(android.app.Application);
}

# Keep optional AAOS linkage behind the runtime automotive feature check.
# Do not inline platform android.car references into the generic Android factory.
-keep,allowobfuscation class dev.carplaymate.demo.AutomotiveCarAccessProvider { *; }

# SDK Gson models are constructed/read by reflection; preserve their field names.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class ai.pleos.playground.stt.data.** { *; }
-keep class ai.pleos.playground.stt.constant.SttFinalResult { *; }
-keep class ai.pleos.playground.caas.service.speech.data.tts.** { *; }

# universal-access 0.6.0 ships consumer rules for its JNA/JNI/security classes.
# Keep those upstream rules active; do not replace or suppress unresolved classes.
