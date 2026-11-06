# Full R8 optimization is required for real Pleos speech. Debug capability is disabled.
# A final demo/release build must fail if either SDK logger survives shrinking.
-checkdiscard class ai.pleos.playground.stt.utils.Log
-checkdiscard class ai.pleos.playground.tts.utils.Log
