package dev.carplaymate.demo

/** App-authored guidance only. Never render SDK, network or captured text as a recovery notice. */
internal data class RecoveryNotice(
    val title: String,
    val detail: String,
    val settingsLabel: String? = "이용 방식 확인하기",
    val returnHome: Boolean = false,
)

internal fun RecoveryCause.notice(hasResumeTarget: Boolean = true): RecoveryNotice = when (this) {
    RecoveryCause.INPUT_PERMISSION -> RecoveryNotice("마이크 권한을 확인해 주세요",
        "기기 앱 설정에서 마이크 또는 차량 음성 입력 권한을 확인해 주세요. 돌아온 뒤 직접 이어갈 수 있어요.", "마이크 권한 확인하기")
    RecoveryCause.INPUT_UNAVAILABLE -> RecoveryNotice("음성 입력을 준비하지 못했어요",
        "이 기기의 음성 입력을 사용할 수 없어요. 설정에서 ‘듣고 선택하기’나 ‘읽으며 체험’을 직접 고를 수 있어요.", "다른 이용 방식 고르기")
    RecoveryCause.INPUT_LANGUAGE -> RecoveryNotice("한국어 음성 입력이 필요해요",
        "이 기기의 한국어 인식 모델을 사용할 수 없어요. 설정에서 ‘듣고 선택하기’나 ‘읽으며 체험’을 직접 고를 수 있어요.", "다른 이용 방식 고르기")
    RecoveryCause.INPUT_BUSY -> RecoveryNotice("음성 입력이 사용 중이에요",
        "다른 기능이 음성 입력을 사용하고 있어요. 그 기능을 마친 뒤 이어하기로 다시 시도해 주세요.")
    RecoveryCause.INPUT_TIMEOUT -> RecoveryNotice("대답 확인이 늦어졌어요",
        "입력을 멈추고 쉬고 있어요. 준비됐을 때 이어하기로 다시 시도해 주세요.")
    RecoveryCause.INPUT_FAILED -> RecoveryNotice("대답을 확인하지 못했어요",
        "잠깐 쉬었다가 이어하기로 다시 말해 주세요. 설정에서 다른 이용 방식을 직접 골라도 좋아요.")
    RecoveryCause.AUDIO_FOCUS -> RecoveryNotice("다른 소리 때문에 쉬고 있어요",
        "통화나 다른 소리가 끝나면 직접 이어가 주세요.")
    RecoveryCause.OUTPUT_PERMISSION -> RecoveryNotice("음성 안내 권한을 확인해 주세요",
        "설정에서 음성 안내 권한을 확인해 주세요. 소리 없이 이용하려면 ‘읽으며 체험’을 직접 고를 수 있어요.", "음성 안내 설정 확인하기")
    RecoveryCause.OUTPUT_UNAVAILABLE -> RecoveryNotice("음성 안내를 준비하지 못했어요",
        "설정에서 기기 음성을 다시 확인해 주세요. 소리 없이 이용하려면 ‘읽으며 체험’을 직접 고를 수 있어요.", "음성 안내 설정 확인하기")
    RecoveryCause.OUTPUT_LANGUAGE -> RecoveryNotice("한국어 안내 음성이 필요해요",
        "기기의 한국어 안내 음성을 사용할 수 없어요. 설정에서 기기 음성을 확인하거나 ‘읽으며 체험’을 직접 골라 주세요.", "음성 안내 설정 확인하기")
    RecoveryCause.OUTPUT_FAILED -> RecoveryNotice("이야기 소리가 멈췄어요",
        "음성 안내를 마치지 못했어요. 이어하기로 다시 듣거나 설정에서 ‘읽으며 체험’을 직접 골라 주세요.", "음성 안내 설정 확인하기")
    RecoveryCause.CONNECTION -> RecoveryNotice("연결을 다시 확인해 주세요",
        if (hasResumeTarget) "소리는 멈췄어요. 연결이 돌아온 뒤 이어하기를 누르면 저장된 진행을 다시 확인해요."
        else "아직 이어갈 이야기가 없어요. 홈에서 연결과 이용 방식을 확인한 뒤 다시 시작해 주세요.",
        null, returnHome = !hasResumeTarget)
    RecoveryCause.PROGRESS -> RecoveryNotice("진행을 다시 확인할게요",
        if (hasResumeTarget) "잠시 쉬고 있어요. 이어하기로 진행을 다시 확인해 주세요. 같은 문제가 계속되면 앱을 다시 열어 주세요."
        else "아직 이어갈 이야기가 없어요. 홈에서 연결과 이용 방식을 확인한 뒤 다시 시작해 주세요.",
        null, returnHome = !hasResumeTarget)
    RecoveryCause.SESSION_REPLACED -> RecoveryNotice("최근 이야기를 이어가 주세요",
        "새로 시작한 이야기가 있어요. 홈에서 최근 진행을 골라 이어가 주세요.", null, returnHome = true)
    RecoveryCause.CONTENT_UNAVAILABLE -> RecoveryNotice("이야기 버전을 확인해 주세요",
        "저장된 이야기에 맞는 콘텐츠가 필요해요. 앱 버전을 확인해 주세요. 기존 진행은 남겨 두었어요.", null, returnHome = true)
    RecoveryCause.ADVENTURE_UNSUPPORTED -> RecoveryNotice("기기 내 모험으로 함께해요",
        "선택한 모험은 현재 개발 연결에서 사용할 수 없어요. 보호자 설정에서 기기 내 모험으로 전환해 주세요.", "연결 설정 확인하기", returnHome = true)
    RecoveryCause.PROFILE -> RecoveryNotice("가족 별명을 확인해 주세요",
        "서로 구별되는 1~12글자 별명이 필요해요. 보호자 설정에서 별명을 확인해 주세요.", "보호자 설정 확인하기", returnHome = true)
}
