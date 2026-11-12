# Android 빌드

JDK 17과 Android SDK Platform 35를 준비합니다. 프로젝트의 `apps/vehicle` 디렉터리에서 Windows는 `.\gradlew.bat :app:assembleDemo`, macOS와 Linux는 `bash gradlew :app:assembleDemo`를 실행합니다. 첫 빌드에는 Maven 의존성 다운로드가 필요합니다. 생성한 개발 APK는 `app/build/outputs/apk/demo/app-demo.apk`에 있습니다.

공개 [서명 APK](https://github.com/kimsejun0426/onu-hmg-app-challenge-2026-review/releases/download/v0.58.0-contest/onu-0.58.0-release.apk)의 패키지는 `dev.carplaymate.demo`, 버전은 0.58.0, versionCode는 63입니다. 정식 서명 키는 팀에서 별도로 관리합니다. 앱의 한국어 음원과 그림은 소스에 포함돼 있으며, 앱을 빌드하거나 실행할 때 음성 생성 모델을 다운로드할 필요가 없습니다. 배포 해시는 [릴리스 해시 목록](https://github.com/kimsejun0426/onu-hmg-app-challenge-2026-review/releases/download/v0.58.0-contest/SHA256SUMS.txt)에 기록했습니다.
