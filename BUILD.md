# 빌드와 실행

JDK 17과 Android SDK Platform 35를 준비한 뒤 apps/vehicle에서 실행합니다.

~~~powershell
.\gradlew.bat --no-daemon :app:assembleDemo :app:lintDemo :app:testDemoUnitTest
~~~

macOS·Linux에서는 bash gradlew로 실행합니다. 첫 빌드에는 Maven 의존성을 내려받을 인터넷 연결이 필요합니다. 생성 위치는 app/build/outputs/apk/demo/app-demo.apk입니다.

검증한 서명 APK는 [설치 파일](apk/오누_오누_release.apk)에서 받을 수 있습니다. 출시 빌드에는 별도로 관리하는 서명 키가 필요합니다. 소스 ZIP에는 앱 재현에 필요한 Android 소스·소리·그림이 들어 있습니다. 음성 합성 모델을 실행하지 않고 빌드할 수 있습니다.

기존 모험과 두 소리 편지의 제작 범위 및 출처는 [구성요소 출처](SOURCES.md)에 설명했습니다. 공개 문서에 보존한 0.47 음성 제작 자료는 그 당시 모험의 제작 기록입니다.
