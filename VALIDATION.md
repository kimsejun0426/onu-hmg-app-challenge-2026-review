# 검증 범위

[제공된 최종 release 검증 JSON](verification/final-validation.json)의 조건과 한계를 따릅니다.
이 snapshot 내보내기 자체는 새로운 빌드·사용자 조사·실차 시험을 실행하지 않았습니다.

- The59 selected Android audio/story/storage tests and2 actual Pleos SDK fixed-PCM experiments ran on candidate1 demo APK; they were not rerun on final candidate2. Scoped reuse is supported by source, fixture, test APK and authored asset byte equality.
- The final signed optimized release APK was tested through external actual UI. Pleos SDK fixed synthetic PCM evidence remains from the earlier demo APK and is not described as execution on the submitted release APK.
- The110 JVM tests and6 welcome UI executions were newly run for final candidate2. Earlier-candidate110 JVM/UI6 executions are not added to these counts.
- Emulator host microphone and host audio output were disabled. Playback callbacks, exact rendered PCM and UI state observations do not establish human-audible quality or physical microphone behavior.
- Welcome UI state tests use fake output. Local greeting start, completion and explicit stop were separately observed in the actual final signed release UI.
- Real children, comprehension, enjoyment, human listening, TalkBack speech, car-cabin acoustics, physical microphone input and real driving support remain unvalidated. restrictedVoiceSupported remains false.
- Dynamic nicknames and unbundled narrative TTS remain platform dependent.
- Failed environment/capture-helper/preview attempts are retained separately and excluded from passing test counts.
- No full Python rerun or whole-app UI regression was performed for this narrowly scoped final UI draw change.
