# 오누

우리 자동차가 아이의 여정에 함께하는 친구가 되도록 돕는 한국어 음성 이야기 앱입니다.

팀 오누 · **0.47.0 / versionCode 49** · 공개 심사용 자료

[![오누 49초 소개 영상](film/2026-11-06/poster.png)](film/2026-11-06/오누_소개영상_2026-11-06.mp4)

## 심사 자료

- [설치 APK](apk/오누_오누_release.apk) — 서명된 release, Android 9(API 28) 이상
- [최신 소개 영상](film/2026-11-06/오누_소개영상_2026-11-06.mp4) — 49.3초 · 1920×1080 · 60fps
- [실제 동작 보조 시연](demo/오누_실제동작시연_0.47.0.mp4) — 48.93초 무음 · 읽기 체험의 선택→반응·직접 이어하기 · [구간과 검증 범위](demo/README.md)
- [한국어 자막](film/2026-11-06/captions.srt) · [영상 제작·출처와 검증 범위](film/2026-11-06/provenance/PRODUCTION.md)
- [최신 다운로드 모음](https://github.com/kimsejun0426/onu-hmg-app-challenge-2026-review/releases/tag/v0.47.0-contest)
- [감사된 대응 소스 ZIP](https://github.com/kimsejun0426/onu-hmg-app-challenge-2026-review/releases/download/v0.47.0-contest/onu-0.47.0-reviewer-source.zip)
- [빌드 안내](BUILD.md) · [검증 범위](VALIDATION.md) · [출처와 권리](RIGHTS.md)
- [원본 소스 파일 해시](source-file-manifest.json) · [이 저장소 자료 해시](submission-manifest.json)

소개 영상은 가상의 B2 합성 목소리와 별도 일러스트 연출, 최종 서명 APK의 실제 관심사 설정·읽기 체험 이어하기 화면 7.83초를 담았습니다. 마이크 인식, 실제 아동 응답, 실차 주행을 촬영한 영상은 아닙니다.

보조 시연은 기존 최종 release 녹화 두 개를 원래 속도로 보여 줍니다. 두 구간 사이 쉬기·앱 종료 조작은 영상에 없음을 표시하며, 실제 음성 인식이나 새 앱 시험의 증거로 설명하지 않습니다.

## 설치와 실행 환경

연결한 Android 기기에 아래 명령으로 설치할 수 있습니다.

```text
adb install -r "apk/오누_오누_release.apk"
```

처음 실행에서는 별명·관심사·함께할 방식을 직접 선택합니다. 일반 Android에서 화면과 이야기 흐름을 살펴볼 때는 **읽으며 체험**을 선택하세요. 음성 기능에는 지원되는 Pleos 서비스, 한국어 모델, 권한과 해당 오디오 환경이 필요합니다. 일반 Android 설치·읽기 체험 성공만으로 Pleos 음성 인식을 보장하지 않습니다.

공룡 고정 대사와 공통 안내·첫 인사 59개는 B2 동봉 음원을 사용합니다. 다른 이야기와 동적인 별명 안내는 플랫폼 TTS에 의존합니다. 쉬었다 돌아오면 자동으로 듣거나 말하지 않으며, 직접 시작·이어하기를 선택합니다.

주행 중 아이가 화면을 보거나 만지지 않고 음성으로 함께하는 것은 제품의 필수 목표입니다. 실제 플랫폼의 제한 중 음성 허용은 아직 검증되지 않았고 `restrictedVoiceSupported=false`를 유지합니다. 상시 녹음이나 차내 대화 전문 수집을 전제로 하지 않습니다.

## 확인한 범위

[최종 검증 기록](verification/final-validation.json)은 최종 후보2의 JVM 110개·첫 인사 UI 6회·서명 release의 실제 화면 관찰과, 후보1 demo에서 실행한 Android 59개·실제 Pleos SDK 고정 합성 PCM 2회를 구분합니다. 후자의 재사용 근거는 동일 음성 처리 코드·시험 APK·작성 자산이며 제출 release에서 재실행한 것으로 표시하지 않습니다.

실제 아동의 이해·재이용, 사람의 청취, 물리 마이크·차량 음향·주행 허용은 별도 검증이 남아 있습니다. 이 저장소 갱신은 새 앱 시험이나 공식 접수 완료가 아닙니다. 이 공개 심사용 저장소의 열람·다운로드와 공식 접수 완료는 구분합니다.

## 소스와 이력

비공개 작업 이력 없이 현재 검토 자료를 하나의 snapshot으로 공개했습니다. 이전 저장소의 Git 이력이나 구판 영상은 복사하지 않았습니다.

현재 `apps/vehicle`, `content`, `services`, `packages`, `tests`, `tools`는 감사된 0.47.0 소스 ZIP에서 가져왔습니다. JDK 17·Android SDK Platform 35·Gradle wrapper로 demo를 재현하는 절차는 [BUILD.md](BUILD.md)를 따릅니다. 최종 음원이 포함되어 있어 앱 빌드에는 별도 음성 모델이나 GPU가 필요하지 않습니다. 서명 키는 포함하지 않으며 demo 빌드와 제출 서명 APK의 바이트 재현은 구분합니다.

원본 ZIP의 [안내문](verification/reviewer-snapshot-README.md)과 [패키지 목록](verification/reviewer-snapshot-submission-manifest.json)은 당시 snapshot 기록입니다. 위 원본 목록의 경로는 원본 ZIP의 `onu-reviewer/`를 기준으로 합니다. 이 공개 저장소의 새 README·영상·보조 자료는 현재 [자료 목록](submission-manifest.json)에 별도로 기록합니다.

신청서·개별 연락처·동의·서명 문서, 내부 작업 폴더, 키·캐시·실행 데이터베이스는 포함하지 않습니다. 앱에 필요한 공개 운영자 고지는 보존하며 완전 익명화라고 표시하지 않습니다. `ci/verify-workflow.yml.example`은 자동 실행되지 않는 참고본입니다.
