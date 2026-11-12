# 오누

차창을 두드리는 소리는 빗방울일까, 와이퍼일까? 오누는 아이와 차 안에서 소리를 듣고 한마디씩 주고받는 한국어 여행 놀이입니다. 아이가 고른 답에 따라 펭귄의 점프 중계와 다음 편지가 달라집니다.

[약 104초 차량 시연 보기](https://kimsejun0426.github.io/onu-hmg-app-challenge-2026-review/review-058/) · [첫 소리 편지 직접 해 보기](https://kimsejun0426.github.io/onu-hmg-app-challenge-2026-review/try/) · [설치 파일과 소스 내려받기](https://github.com/kimsejun0426/onu-hmg-app-challenge-2026-review/releases/tag/v0.58.0-contest)

## 차량 화면 규격과 기술

검증한 Pleos Connect 2.1.0 에뮬레이터(Android 14, API 34)의 전체 화면은 2560×1440px, 밀도는 160dpi입니다. 이 정차 화면 구성에서 오누에 배정된 오른쪽 앱 영역은 1682×1210px로 측정했습니다. 앱은 Android용 Kotlin·Jetpack Compose로 구현했고, 한국어 음성 인식과 읽어 주기에 Pleos Playground SpeechToText·TextToSpeech SDK 3.3.1을 사용합니다.

## 빌드와 실행

JDK 17과 Android SDK Platform 35를 준비한 뒤 `apps/vehicle`에서 Windows는 `.\gradlew.bat :app:assembleDemo`, macOS·Linux는 `bash gradlew :app:assembleDemo`를 실행합니다. 개발 APK는 `app/build/outputs/apk/demo/app-demo.apk`에 생성됩니다. 아래 순서로 제출본을 설치해 체험할 수 있습니다.

1. Pleos Connect 2.1 에뮬레이터에 [오누 0.58.0 APK](apk/오누_오누_release.apk)를 설치하고 정차 상태에서 앱을 엽니다. Android SDK를 쓸 때는 `adb install -r "apk/오누_오누_release.apk"`로 설치할 수 있습니다.
2. 아이의 별명과 관심사를 정합니다. 음성 모드는 Pleos 한국어 음성 서비스와 마이크 권한이 필요합니다. 마이크가 없는 에뮬레이터에서는 ‘듣고 선택하기’를 고르세요.
3. 홈의 바다 편지를 열어 어떤 동물이 뛰어들었는지 추측하고, 펭귄이 어떻게 뛰어들지 골라 직접 중계해 보세요. 공룡 이야기에서는 사라진 알 모형을 찾아 물가와 숲 중 한쪽을 탐색합니다.
4. 이야기를 잠깐 쉬면 홈에서 지난 단서로 돌아갈 수 있습니다. ‘다시 듣기’는 같은 질문을 한 번 더 들려줍니다.

영상은 Pleos에서 촬영한 앱 0.55.0의 화면을 사용했습니다. 진행자가 화면으로 답했고 편집 과정에서 앱 음원을 더했습니다. 브라우저 체험은 첫 소리 편지를 화면 선택으로 풀어 보는 별도 자료입니다.

[빌드 방법](BUILD.md) · [0.58.0 검증 범위](VALIDATION.md) · [음원과 구성요소 출처](SOURCES.md) · [권리 고지](RIGHTS.md)
