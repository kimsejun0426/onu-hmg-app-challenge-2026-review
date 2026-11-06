# The independently packaged instrumentation APK reuses these target dependencies.
# Keep their original public/internal binary API in demo; otherwise R8 can remove
# target-unused helpers (observed: androidx.tracing.Trace and kotlin.LazyKt) that
# AndroidJUnitRunner/Compose tests still invoke. Release does not load this file.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.** { *; }

# Instrumentation injects public SDK error values that production code need not
# reference directly (observed: STT ErrorType.TIMEOUT). Preserve the enum API of
# both speech SDKs so independently packaged test callbacks retain those fields.
-keep public enum ai.pleos.playground.stt.constant.** { *; }
-keep public enum ai.pleos.playground.tts.constant.** { *; }

# Opt-in PCM instrumentation calls the published ON_DEVICE STT API from its
# separate APK. Keep only that binary surface; no microphone request(), server
# registration or AmbientAi API is exposed by this test. The production input
# adapter still uses its unchanged request() path outside this explicit test.
-keep class ai.pleos.playground.stt.SpeechToText {
    public <init>(android.content.Context, ai.pleos.playground.stt.constant.Mode);
    public void initialize(ai.pleos.playground.stt.listener.OnInitializeListener);
    public void addListener(ai.pleos.playground.stt.listener.ResultListener);
    public void removeListener(ai.pleos.playground.stt.listener.ResultListener);
    public void setLocale(java.util.Locale);
    public void sendAudio(byte[]);
    public void completeAudioSend();
    public void stop();
    public void release();
}
-keep interface ai.pleos.playground.stt.listener.OnInitializeListener { *; }
-keep interface ai.pleos.playground.stt.listener.ResultListener { *; }

# Other ai.pleos.* classes remain eligible for optimization: SDK logger
# elimination and -checkdiscard remain mandatory, and
# tools/check_pleos_sdk_logs.py checks the final APK DEX.

# The separately packaged mock-service runner subclasses this Application.
# R8 otherwise marks the open getter final (observed in the 0.32 first APK).
# Keep only this demo ABI; market release does not load these rules.
-keep class dev.carplaymate.demo.OnuApplication {
    public <init>();
    public dev.carplaymate.demo.VoiceSessionHost getVoiceSessionHost$app_demo();
}
