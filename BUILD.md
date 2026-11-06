# 빌드와 재현

JDK 17, Android SDK Platform 35와 해당 Build Tools, 포함된 Gradle wrapper를 사용합니다.
정확한 플러그인·SDK 의존성은 apps/vehicle의 Gradle 파일을 따릅니다. 최초 의존성 다운로드에는
공급자 Maven 저장소 접근과 이용 조건이 적용됩니다. SDK 바이너리는 포함하지 않았습니다.

서명 키 없이 개발본을 빌드하려면:

~~~powershell
Set-Location apps/vehicle
.\gradlew.bat --no-daemon :app:assembleDemo :app:lintDemo :app:testDemoUnitTest
~~~

demo 출력은 app/build/outputs/apk/demo/app-demo.apk입니다. 제출 release APK와는 패키지·서명이 다릅니다.
release 생성에는 소유자가 별도로 관리하는 키·암호·독립 확인한 공개 인증서 pin이 필요합니다.
그 값은 이 snapshot에 없으며 일반 심사 재현에서 제공하거나 요구하지 않습니다.
빌드 재현과 제출 서명 APK의 바이트 일치는 구분합니다.

효과음 원본·dry 목소리·제작 고지는 포함했지만 이전 혼합본 비교용 baseline은 이 snapshot에 없습니다. 앱 빌드에는 동봉된 최종 WAV를 사용합니다.

최종 B2 음원은 앱에 동봉돼 있어 합성 환경 없이 빌드할 수 있습니다. 재합성이 필요한 경우
[고정 제작 계획](docs/audio/onu-0.47.0-voice-plan.json),
[참조 정보](docs/audio/reproduction/onu-b2/REFERENCE.json)와
`python tools/build_qwen_voice.py --help`를 따릅니다.
로컬 모델, CUDA 지원 GPU와 별도 Qwen 의존성이 필요하며 기본 앱·참조 API 의존성에 섞지 않습니다.
재현 도구는 온라인 다운로드를 하지 않습니다. GPU와 라이브러리 차이에 따른 음원 바이트 차이는
가능하므로 텍스트·문장 끝·신호·청취를 다시 검토해야 합니다.
