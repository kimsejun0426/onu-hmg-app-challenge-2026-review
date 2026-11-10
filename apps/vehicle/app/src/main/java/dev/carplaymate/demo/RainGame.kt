package dev.carplaymate.demo

import java.text.Normalizer

internal enum class RainStep { READY, DROPS, WIPER, TEMPO, DIRECT, END }
internal enum class RainEnding { DROPS, WIPER }

internal data class RainState(
    val step: RainStep = RainStep.READY,
    val previousJump: JumpEnding? = null,
    val ending: RainEnding? = null,
    val assisted: Boolean = false,
    val paused: Boolean = false,
)

internal data class RainTurn(
    val state: RainState,
    val title: String,
    val text: String,
    val cues: List<String>,
    val choices: List<JumpChoice>,
    val acceptsAnswer: Boolean,
)

/** Store only fixed game flags and enum IDs. Microphone input is never placed in a checkpoint. */
internal fun RainState.rainCheckpoint(): String =
    listOf(step.name, previousJump?.name.orEmpty(), ending?.name.orEmpty(), assisted.toString(), paused.toString())
        .joinToString("|")

internal fun restoreRain(saved: String?): RainState? {
    if (saved == null || saved.length !in 1..96) return null
    return runCatching {
        val parts = saved.split('|')
        require(parts.size == 5)
        val state = RainState(
            RainStep.valueOf(parts[0]),
            parts[1].takeIf(String::isNotEmpty)?.let(JumpEnding::valueOf),
            parts[2].takeIf(String::isNotEmpty)?.let(RainEnding::valueOf),
            parts[3].toBooleanStrict(),
            parts[4].toBooleanStrict(),
        )
        require((state.step == RainStep.END) == (state.ending != null))
        if (state.step == RainStep.READY || state.step == RainStep.END) require(!state.assisted && !state.paused)
        state
    }.getOrNull()
}

internal object RainGame {
    private val lines = mapOf(
        "opening" to "펭귄한테 편지가 왔어. 비 오는 날 우리 차 소리로 방송을 열고 싶대. 우리 둘이 음악을 만들자!",
        "memory_soft" to "지난번 살금살금, 첨벙! 그 점프 중계가 좋았대.",
        "memory_bold" to "지난번 성큼성큼, 퐁당! 그 점프 중계가 좋았대.",
        "rain_drops" to "톡, 톡… 톡톡.",
        "drops_q" to "톡톡, 차창을 두드린 건 뭘까?",
        "rain_ack" to "차창에 떨어진 빗방울이야. 톡톡!",
        "wiper" to "슥, 삭… 슥, 삭.",
        "wiper_q" to "이번엔 앞유리에서 어떤 소리가 났어?",
        "wiper_ack" to "앞유리를 슥삭 닦는 와이퍼야!",
        "rain_beat" to "톡… 슥삭… 톡톡톡, 슥삭슥삭!",
        "tempo_q" to "앞뒤를 비교해 봐. 두 소리가 나중엔 어떻게 달라졌어?",
        "tempo_hint" to "처음엔 톡, 톡. 나중엔 톡톡톡! 소리 사이가 벌어졌어, 좁아졌어?",
        "tempo_ack" to "소리 사이가 짧아졌어. 빗방울도, 와이퍼도 점점 빨라졌지!",
        "direct_q" to "우리 방송의 첫 소리, 빗방울 톡톡부터 할까? 와이퍼 슥삭부터 할까?",
        "rain_hint" to "하늘에서 떨어져 차창을 두드려. 누구 소리일까?",
        "wiper_hint" to "앞유리를 왼쪽 오른쪽으로 쓱 닦는 건 뭘까?",
        "finale_drops" to "[빗방울 톡톡으로 시작하는 방송 음악] 펭귄한테 보냈어! 다음 방송은 우리가 만든 이 음악으로 시작한대!",
        "finale_wiper" to "[와이퍼 슥삭으로 시작하는 방송 음악] 펭귄한테 보냈어! 다음 방송은 우리가 만든 이 음악으로 시작한대!",
        "unheard" to FirstJumpGame.caption("unheard"),
        "repeat_prompt" to FirstJumpGame.caption("repeat_prompt"),
    )

    private val soundIds = setOf("rain_drops", "wiper", "rain_beat")
    private val soundChoices = listOf(JumpChoice("drops", "빗방울"), JumpChoice("wiper", "와이퍼"))
    private val tempoChoices = listOf(JumpChoice("faster", "점점 빨라졌어"), JumpChoice("slower", "점점 느려졌어"))
    private val directChoices = listOf(JumpChoice("drops", "톡톡 먼저"), JumpChoice("wiper", "슥삭 먼저"))

    fun caption(cue: String): String = lines.getValue(cue)
    fun isEffect(cue: String): Boolean = cue in soundIds

    private fun cues(state: RainState): List<String> = when (state.step) {
        RainStep.READY -> emptyList()
        RainStep.DROPS -> listOf("rain_drops", "drops_q")
        RainStep.WIPER -> listOf("wiper", "wiper_q")
        RainStep.TEMPO -> listOf("rain_beat", "tempo_q")
        RainStep.DIRECT -> listOf("direct_q")
        RainStep.END -> listOf(if (state.ending == RainEnding.WIPER) "finale_wiper" else "finale_drops")
    }

    private fun describe(state: RainState, plan: List<String> = cues(state)): RainTurn {
        if (state.paused) return RainTurn(state, "잠깐 쉬자", "같은 소리에서 다시 이어갈 수 있어.",
            emptyList(), listOf(JumpChoice("resume", "여기서 이어가기")), false)
        val (title, choices) = when (state.step) {
            RainStep.READY -> "비 오는 날의 방송 음악" to listOf(JumpChoice("open", "편지 열기"))
            RainStep.DROPS -> "차창을 톡톡 두드린 건?" to soundChoices
            RainStep.WIPER -> "앞유리를 오가는 소리" to soundChoices
            RainStep.TEMPO -> "두 소리가 어떻게 달라졌지?" to tempoChoices
            RainStep.DIRECT -> "첫 소리는 네가 골라 줘" to directChoices
            RainStep.END -> "펭귄의 방송 음악을 보냈어" to listOf(JumpChoice("remix", "다른 순서로 듣기"), JumpChoice("restart", "편지 처음부터"))
        }
        val text = if (state.step == RainStep.READY) "펭귄이 우리 차의 빗소리를 듣고 싶대. 방송 첫 음악을 함께 만들자."
            else plan.joinToString(" ", transform = ::caption)
        return RainTurn(state, title, text, plan, choices, state.step != RainStep.READY && state.step != RainStep.END)
    }

    fun initial(previousJump: JumpEnding? = null): RainTurn = describe(RainState(previousJump = previousJump))
    fun resume(state: RainState): RainTurn = describe(state)

    private fun move(state: RainState, step: RainStep, vararg before: String): RainTurn {
        val moved = state.copy(step = step, ending = null, assisted = false, paused = false)
        return describe(moved, before.toList() + cues(moved))
    }

    private fun finish(state: RainState, ending: RainEnding, before: List<String> = emptyList()): RainTurn {
        val done = state.copy(step = RainStep.END, ending = ending, assisted = false, paused = false)
        return describe(done, before + cues(done))
    }

    private fun answerForChild(state: RainState): RainTurn = when (state.step) {
        RainStep.DROPS -> move(state, RainStep.WIPER, "rain_ack")
        RainStep.WIPER -> move(state, RainStep.TEMPO, "wiper_ack")
        RainStep.TEMPO -> move(state, RainStep.DIRECT, "tempo_ack")
        RainStep.DIRECT -> finish(state, RainEnding.DROPS)
        else -> describe(state)
    }

    private fun hint(state: RainState): RainTurn {
        if (state.assisted) return answerForChild(state)
        val helped = state.copy(assisted = true)
        val plan = when (state.step) {
            RainStep.DROPS -> listOf("rain_hint", "rain_drops", "drops_q")
            RainStep.WIPER -> listOf("wiper_hint", "wiper", "wiper_q")
            RainStep.TEMPO -> listOf("rain_beat", "tempo_hint")
            else -> cues(state)
        }
        return describe(helped, plan)
    }

    private fun unanswered(state: RainState, silence: Boolean): RainTurn {
        if (!state.assisted) return if (silence) hint(state)
            else describe(state.copy(assisted = true), listOf("unheard") + cues(state))
        if (!silence) return answerForChild(state)
        // The child can listen to the finished music after one quiet invitation.
        val rest = when (state.step) {
            RainStep.DROPS -> listOf("rain_ack", "wiper", "wiper_ack")
            RainStep.WIPER -> listOf("wiper_ack")
            RainStep.TEMPO -> listOf("tempo_ack")
            else -> emptyList()
        }
        return finish(state, RainEnding.DROPS, rest)
    }

    fun choose(state: RainState, id: String): RainTurn {
        if (state.paused) return if (id == "resume") describe(state.copy(paused = false)) else describe(state)
        if (state.step == RainStep.READY) {
            if (id != "open") return describe(state)
            val intro = when (state.previousJump) {
                JumpEnding.SOFT -> listOf("opening", "memory_soft")
                JumpEnding.BOLD -> listOf("opening", "memory_bold")
                null -> listOf("opening")
            }
            return move(state, RainStep.DROPS, *intro.toTypedArray())
        }
        if (state.step == RainStep.END) return when (id) {
            "remix" -> move(state, RainStep.DIRECT)
            "restart" -> initial(state.previousJump)
            else -> describe(state)
        }
        when (id) {
            "rest" -> return describe(state.copy(paused = true))
            "again" -> return describe(state, listOf("repeat_prompt") + cues(state))
            "hint" -> return hint(state)
        }
        return when (state.step) {
            RainStep.DROPS -> when (id) {
                "drops" -> answerForChild(state)
                "wiper" -> hint(state)
                // Verify the second sound before moving past the child's early identification.
                "pair" -> move(state, RainStep.TEMPO, "rain_ack", "wiper", "wiper_ack")
                "pair_fast" -> move(state, RainStep.DIRECT, "rain_ack", "wiper", "wiper_ack", "rain_beat", "tempo_ack")
                else -> describe(state)
            }
            RainStep.WIPER -> when (id) {
                "wiper", "pair" -> answerForChild(state)
                "drops" -> hint(state)
                "pair_fast", "wiper_fast" -> move(state, RainStep.DIRECT, "wiper_ack", "rain_beat", "tempo_ack")
                else -> describe(state)
            }
            RainStep.TEMPO -> when (id) {
                "faster", "pair_fast", "wiper_fast" -> answerForChild(state)
                "slower", "same", "volume" -> hint(state)
                else -> describe(state)
            }
            RainStep.DIRECT -> when (id) {
                "drops" -> finish(state, RainEnding.DROPS)
                "wiper" -> finish(state, RainEnding.WIPER)
                else -> describe(state)
            }
            else -> describe(state)
        }
    }

    fun hear(state: RainState, text: String?): RainTurn {
        if (state.paused || state.step == RainStep.READY || state.step == RainStep.END) return resume(state)
        if (text.isNullOrBlank()) return unanswered(state, true)
        if (QuietRequest.matches(text)) return choose(state, "rest")
        val intent = RainAnswers.match(state.step, text) ?: return unanswered(state, false)
        return choose(state, intent)
    }
}

private object RainAnswers {
    private val address = Regex("^오누(?:야|야아)[\\s,，.!?]+")
    private val help = setOf("힌트", "힌트줘", "힌트주세요", "도와줘", "잘모르겠어", "모르겠어", "몰라", "모르겠어요", "알려줘", "오누가해줘")
    private val pause = setOf("잠깐쉬자", "잠깐쉴래", "잠깐쉬고싶어", "이제쉬자", "여기서멈춰")
    private val replay = setOf("다시", "다시듣기", "다시들려줘", "다시들어볼래", "한번더", "한번더들려줘", "또들려줘")

    // Whole expressions matter. "비밀" and "빗방울은 아니야" must never mean rain.
    private const val RAIN = "(?:빗방울|빗물|빗소리|비|물방울|톡톡톡|톡톡|토독토독|투둑투둑)"
    private const val WIPER = "(?:앞유리닦개|유리닦개|와이퍼|쓱싹쓱싹|슥삭슥삭|쓱싹|슥삭|쓱쓱|슥슥)"
    private const val ENDING = "(?:소리)?(?:야|이야|이다|이에요|예요|네요|인것같아|인거같아|일것같아|같아|가들려|가들렸어|였어|이었어)?"
    private val rain = Regex("(?:이건|그건)?$RAIN$ENDING")
    private val wiper = Regex("(?:이건|그건)?$WIPER$ENDING")
    private val rainPhrases = setOf("비가와", "비가오는소리", "비오는소리", "빗방울이떨어져", "빗방울이톡톡떨어졌어",
        "물방울이창문을두드리는소리", "창문에비가떨어졌어", "빗방울이창문에떨어져", "차창에빗방울이떨어졌어", "톡톡빗방울", "톡톡빗방울이야")
    private val wiperPhrases = setOf("유리닦는거", "유리창닦는거", "앞유리닦는거", "앞유리닦는소리", "유리닦는소리",
        "와이퍼가유리를닦아", "유리닦는와이퍼야", "앞유리닦는와이퍼야", "와이퍼가슥삭거려", "슥삭와이퍼", "슥삭와이퍼야")
    private const val FASTER = "(?:(?:나중엔|나중에는|나중에|갈수록|뒤로갈수록|처음보다)?(?:두소리가|둘다|소리가)?(?:점점|더|훨씬)?(?:빨라졌어|빨라져|빨라|빨라졌어요|빨라졌지|빨랐어|빨라진것같아)|(?:소리)?(?:사이|간격)(?:가|이)?(?:더|점점)?(?:좁아졌어|짧아졌어|가까워졌어))"
    private val faster = Regex(FASTER)
    private val slower = setOf("느려", "느려져", "느려졌어", "더느려졌어", "점점느려졌어", "나중엔느려졌어", "사이가벌어졌어", "간격이길어졌어", "느렸어")
    private val same = setOf("같아", "그대로", "똑같아", "그대로야", "안바뀌었어", "그냥똑같았어")
    private const val PAIR = "(?:(?:톡톡은)?$RAIN(?:이랑|하고|이(?:고|야)|랑|과|와|다음에|그다음|다음|먼저)?(?:슥삭은)?$WIPER|$WIPER(?:랑|이랑|하고|와|그다음|다음)?$RAIN)(?:소리)?(?:야|이야|같아)?"
    private val pair = Regex(PAIR)
    private val pairFast = Regex("$PAIR(?:가|이|인데|이고|인데도)?$FASTER")
    private val wiperFast = Regex("$WIPER(?:도|가|야|는)?$FASTER")
    private const val FIRST = "(?:소리)?(?:가|이|을|를)?(?:부터|먼저)(?:해줘|해|할래|하자|시작해|시작하자|야|요)?"
    private val rainFirst = Regex("$RAIN(?:$FIRST(?:그(?:다음|다음엔|다음에)|다음에는|다음에|그리고|뒤에)?(?:$WIPER(?:넣어줘|해줘|야|나중에|나중)?)?)?")
    private val wiperFirst = Regex("$WIPER(?:$FIRST(?:그(?:다음|다음엔|다음에)|다음에는|다음에|그리고|뒤에)?(?:$RAIN(?:넣어줘|해줘|야|나중에|나중)?)?)?")

    fun match(step: RainStep, original: String): String? {
        if (original.length > 120) return null
        val normalized = Normalizer.normalize(original.trim(), Normalizer.Form.NFKC)
        val word = address.replaceFirst(normalized, "").filter(Char::isLetterOrDigit)
        if (word in pause) return "rest"
        if (word in help) return "hint"
        if (word in replay) return "again"
        return when (step) {
            RainStep.DIRECT -> when {
                rainFirst.matches(word) -> "drops"
                wiperFirst.matches(word) -> "wiper"
                else -> null
            }
            RainStep.DROPS, RainStep.WIPER -> when {
                pairFast.matches(word) -> "pair_fast"
                pair.matches(word) -> "pair"
                step == RainStep.WIPER && wiperFast.matches(word) -> "wiper_fast"
                rain.matches(word) || word in rainPhrases -> "drops"
                wiper.matches(word) || word in wiperPhrases -> "wiper"
                else -> null
            }
            RainStep.TEMPO -> when {
                word == "빨리" || faster.matches(word) || pairFast.matches(word) || wiperFast.matches(word) -> "faster"
                word in slower -> "slower"
                word in setOf("커졌어", "소리가커졌어", "소리가더커졌어", "더컸어") -> "volume"
                word in same -> "same"
                else -> null
            }
            else -> null
        }
    }
}
