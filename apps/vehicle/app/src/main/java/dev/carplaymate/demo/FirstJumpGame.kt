package dev.carplaymate.demo

import java.text.Normalizer

internal enum class JumpStep { READY, WATER, GUESS, QUESTION, IDENTIFY, DIRECT, END }
internal enum class JumpGuess { OTTER, PENGUIN, BOTH, OTHER }
internal enum class JumpEnding { SOFT, BOLD }

/** The only information needed to restore a round. No speech or transcript is kept. */
internal data class JumpState(
    val step: JumpStep = JumpStep.READY,
    val guess: JumpGuess? = null,
    val ending: JumpEnding? = null,
    val assisted: Boolean = false,
    val paused: Boolean = false,
    val revealedWithHelp: Boolean = false,
)

internal data class JumpChoice(val id: String, val label: String)

internal data class JumpTurn(
    val state: JumpState,
    val title: String,
    val text: String,
    val cues: List<String>,
    val choices: List<JumpChoice>,
    val acceptsAnswer: Boolean,
    val answerLabel: String? = null,
)

internal object FirstJumpGame {
    private val lines = mapOf(
        "opening" to "어? 우리 차에 편지가 왔어. 어제는 무서워서 못 뛰었대. 오늘은 어땠을까? 그 친구의 첫 점프를 같이 중계해 주자.",
        "splash" to "타닥타닥… 첨벙!",
        "water_q" to "어떤 소리가 들렸어?",
        "water_ack" to "응, 물이 튀었어! 뭐가 빠졌나 봐.",
        "steps_ack" to "발소리 들었구나. 나도 타닥타닥 들었어.",
        "sounds_ack" to "물소리도 발소리도 들었구나. 타닥타닥 걷다가 첨벙!",
        "candidates" to "편지에 힌트가 있어. 해달 아니면 펭귄이래.",
        "guess_q" to "지금은 누가 떠올라?",
        "guess_otter" to "해달? 나도 해달이 떠올랐어.",
        "guess_penguin" to "펭귄 같아? 난 아직 잘 모르겠어.",
        "guess_both" to "둘 다 떠오르지? 나도 아직 헷갈려.",
        "ask_question" to "해달도 펭귄도 헤엄을 잘 쳐. 깃털이 있는지 물어볼까? 날개로 헤엄치는지 물어볼까?",
        "weak_shared" to "둘 다 물에서 헤엄을 친대. 몸에 뭐가 났는지 물어볼까?",
        "feather_evidence" to "답장 왔다! 깃털이 있대. 날개로 헤엄친대.",
        "who_q" to "누가 보낸 편지일까?",
        "reveal" to "편지를 보낸 친구는 펭귄이었어! 첫 점프를 어떤 소리로 들려줄까?",
        "reveal_penguin" to "아까 펭귄이라고 했지? 정말 펭귄이네! 첫 점프를 같이 들려주자.",
        "reveal_otter" to "나도 해달인 줄 알았는데, 깃털이 있었네! 펭귄의 첫 점프를 같이 들려주자.",
        "reveal_unsure" to "이제 알겠다. 깃털이 있는 펭귄이네! 첫 점프를 같이 들려주자.",
        "reveal_help" to "해달한테는 털이 있고, 펭귄한테는 깃털이 있어. 그러니까 이 친구는 펭귄이네!",
        "direct_q" to "살금살금 걷다가 크게 첨벙할까? 아니면 성큼성큼 걷다가 작게 퐁당할까?",
        "finale_soft" to "펭귄 선수가 바위 끝까지 살금살금 다가옵니다. 하나, 둘, 폴짝! 물 위로 고개가 쏙! 첫 점프 성공이야.",
        "finale_bold" to "펭귄 선수가 바위 끝까지 성큼성큼 다가옵니다. 하나, 둘, 폴짝! 물 위로 고개가 쏙! 첫 점프 성공이야.",
        "repeat_prompt" to "좋아. 다시 한번 들어 보자.",
        "hint_body" to "몸을 덮은 게 털일까, 깃털일까? 그걸 물어보자.",
        "water_hint" to "타닥타닥 걷더니, 첨벙! 어디에 뛰어든 걸까?",
        "unheard" to "내가 잘 못 들었어. 한 번만 더 말해 줄래?",
    )

    fun caption(cue: String): String = lines.getValue(cue)

    private val options = mapOf(
        JumpStep.READY to listOf(JumpChoice("open", "편지 열기")),
        JumpStep.WATER to listOf(JumpChoice("wet", "물소리"), JumpChoice("steps", "발소리")),
        JumpStep.GUESS to listOf(JumpChoice("otter", "해달"), JumpChoice("penguin", "펭귄"), JumpChoice("both", "둘 다 가능해")),
        JumpStep.QUESTION to listOf(JumpChoice("feathers", "깃털이 있어?"), JumpChoice("swim", "수영 잘해?"), JumpChoice("wings", "날개로 헤엄쳐?")),
        JumpStep.IDENTIFY to listOf(JumpChoice("penguin", "펭귄"), JumpChoice("otter", "해달")),
        JumpStep.DIRECT to listOf(JumpChoice("soft", "살금살금, 크게 첨벙!"), JumpChoice("bold", "성큼성큼, 작게 퐁당!")),
        JumpStep.END to listOf(JumpChoice("remix", "다른 점프로 중계하기"), JumpChoice("restart", "편지 처음부터")),
    )

    private fun revealCue(state: JumpState): String = if (state.revealedWithHelp) "reveal_help" else when (state.guess) {
        JumpGuess.PENGUIN -> "reveal_penguin"
        JumpGuess.OTTER -> "reveal_otter"
        else -> "reveal_unsure"
    }

    private fun defaultCues(state: JumpState): List<String> = when (state.step) {
        JumpStep.READY -> emptyList()
        JumpStep.WATER -> listOf("splash", "water_q")
        JumpStep.GUESS -> listOf("candidates", "guess_q")
        JumpStep.QUESTION -> listOf("ask_question")
        JumpStep.IDENTIFY -> listOf("feather_evidence", "who_q")
        JumpStep.DIRECT -> listOf(revealCue(state), "direct_q")
        JumpStep.END -> listOf(if (state.ending == JumpEnding.BOLD) "finale_bold" else "finale_soft")
    }

    private fun describe(state: JumpState, cues: List<String> = defaultCues(state)): JumpTurn {
        if (state.paused) return JumpTurn(state, "잠깐 쉬자", "준비되면 같은 자리에서 이어가자.", emptyList(), listOf(JumpChoice("resume", "여기서 이어가기")), false)
        val title = when (state.step) {
            JumpStep.READY -> "첫 점프를 들려줘!"
            JumpStep.WATER -> "점프 끝에 어떤 소리가 났지?"
            JumpStep.GUESS -> "누가 뛰었을까?"
            JumpStep.QUESTION -> "둘을 어떻게 구별할까?"
            JumpStep.IDENTIFY -> "이제 누굴 소개할까?"
            JumpStep.DIRECT -> "네가 소리 감독이야"
            JumpStep.END -> "첫 점프 방송이 완성됐어"
        }
        val text = if (state.step == JumpStep.READY) "어제 못 뛰었던 친구가 보낸 편지야. 첫 점프를 같이 중계해 주자." else cues.joinToString(" ") { lines.getValue(it) }
        return JumpTurn(state, title, text, cues, options.getValue(state.step), state.step != JumpStep.READY && state.step != JumpStep.END)
    }

    fun initial() = describe(JumpState())
    fun resume(state: JumpState) = describe(state)

    private fun next(state: JumpState, step: JumpStep, vararg cues: String, guess: JumpGuess? = state.guess): JumpTurn {
        val moved = state.copy(step = step, guess = guess, assisted = false, paused = false)
        return describe(moved, if (cues.isEmpty()) defaultCues(moved) else cues.toList())
    }

    private fun question(state: JumpState, guess: JumpGuess?): JumpTurn {
        val cues = mutableListOf<String>()
        if (state.step == JumpStep.WATER || guess == JumpGuess.OTHER) cues += "candidates"
        when (guess) {
            JumpGuess.OTTER -> cues += "guess_otter"
            JumpGuess.PENGUIN -> cues += "guess_penguin"
            JumpGuess.BOTH -> cues += "guess_both"
            JumpGuess.OTHER, null -> Unit
        }
        cues += "ask_question"
        return next(state, JumpStep.QUESTION, *cues.toTypedArray(), guess = guess)
    }

    private fun fastAnswer(state: JumpState): JumpTurn {
        val cues = if (state.step == JumpStep.WATER) arrayOf("candidates", "feather_evidence", "reveal_penguin", "direct_q")
            else arrayOf("feather_evidence", "reveal_penguin", "direct_q")
        return next(state, JumpStep.DIRECT, *cues, guess = JumpGuess.PENGUIN)
    }

    private fun finish(state: JumpState, ending: JumpEnding): JumpTurn {
        val ended = state.copy(step = JumpStep.END, ending = ending, assisted = false, paused = false)
        return describe(ended)
    }

    private fun explainSender(state: JumpState): JumpTurn =
        next(state.copy(revealedWithHelp = true), JumpStep.DIRECT)

    private fun hint(state: JumpState): JumpTurn = when (state.step) {
        JumpStep.WATER -> if (state.assisted) {
            next(state, JumpStep.GUESS, "water_ack", "candidates", "guess_q")
        } else {
            describe(state.copy(assisted = true), listOf("water_hint", "splash", "water_q"))
        }
        JumpStep.GUESS -> question(state, null)
        JumpStep.QUESTION -> next(state, JumpStep.IDENTIFY, "hint_body", "feather_evidence", "who_q")
        JumpStep.IDENTIFY -> if (state.assisted) explainSender(state) else describe(state.copy(assisted = true), defaultCues(state))
        JumpStep.DIRECT -> if (state.assisted) {
            describe(state.copy(paused = true))
        } else {
            describe(state.copy(assisted = true), listOf("direct_q"))
        }
        else -> describe(state)
    }

    private fun noMatch(state: JumpState, silent: Boolean): JumpTurn {
        if (!state.assisted) return if (silent) {
            // A spoken "모르겠어" asks for a clue. A quiet child gets one invitation, even at the guess.
            val clue = when (state.step) {
                JumpStep.WATER -> listOf("water_hint", "splash", "water_q")
                JumpStep.GUESS -> defaultCues(state)
                JumpStep.QUESTION -> listOf("hint_body") + defaultCues(state)
                JumpStep.IDENTIFY -> defaultCues(state)
                else -> defaultCues(state)
            }
            describe(state.copy(assisted = true), clue)
        } else describe(state.copy(assisted = true), listOf("unheard") + defaultCues(state))
        if (silent) return describe(state.copy(paused = true))
        return when (state.step) {
            JumpStep.WATER -> next(state, JumpStep.GUESS, "water_hint", "candidates", "guess_q")
            JumpStep.GUESS -> describe(state.copy(paused = true))
            JumpStep.QUESTION -> next(state, JumpStep.IDENTIFY, "hint_body", "feather_evidence", "who_q")
            JumpStep.IDENTIFY -> explainSender(state)
            JumpStep.DIRECT -> describe(state.copy(paused = true))
            else -> describe(state)
        }
    }

    /** A short, authored summary of this answer only; never part of the saved story state. */
    private fun answerLabel(state: JumpState, id: String): String? {
        if (state.paused || state.step in setOf(JumpStep.READY, JumpStep.END)) return null
        val accepted = when (state.step) {
            JumpStep.WATER -> setOf("wet", "steps", "sounds", "otter", "penguin", "both", "outside", "fast", "body")
            JumpStep.GUESS -> setOf("otter", "penguin", "both", "outside", "fast", "body")
            JumpStep.QUESTION -> setOf("feathers", "wings", "body", "swim", "penguin", "otter", "fast")
            JumpStep.IDENTIFY -> setOf("penguin", "otter", "outside", "both", "fast")
            JumpStep.DIRECT -> setOf("soft", "bold")
            else -> emptySet()
        }
        if (id !in accepted) return null
        return options.getValue(state.step).firstOrNull { it.id == id }?.label ?: when (id) {
            "penguin" -> "펭귄"
            "otter" -> "해달"
            "both" -> "둘 다 가능해"
            "sounds" -> "물소리와 발소리"
            "outside" -> "다른 동물"
            "body" -> "몸의 특징 물어보기"
            "fast" -> "단서로 떠올린 펭귄"
            else -> null
        }
    }

    fun choose(state: JumpState, id: String): JumpTurn =
        chooseWithoutLabel(state, id).copy(answerLabel = answerLabel(state, id))

    private fun chooseWithoutLabel(state: JumpState, id: String): JumpTurn {
        if (state.paused) return if (id == "resume") describe(state.copy(paused = false)) else describe(state)
        if (id == "restart" && state.step == JumpStep.END) return initial()
        if (id == "remix" && state.step == JumpStep.END) return next(state.copy(ending = null), JumpStep.DIRECT, "direct_q")
        if (id == "open" && state.step == JumpStep.READY) return next(state, JumpStep.WATER, "opening", "splash", "water_q")
        if (state.step == JumpStep.READY || state.step == JumpStep.END) return describe(state)
        when (id) {
            "rest" -> return describe(state.copy(paused = true))
            "again" -> return describe(state, listOf("repeat_prompt") + defaultCues(state))
            "hint" -> return hint(state)
        }
        return when (state.step) {
            JumpStep.WATER, JumpStep.GUESS -> when (id) {
                "sounds" -> if (state.step == JumpStep.WATER) next(state, JumpStep.GUESS, "sounds_ack", "candidates", "guess_q") else describe(state)
                "wet" -> if (state.step == JumpStep.WATER) next(state, JumpStep.GUESS, "water_ack", "candidates", "guess_q") else describe(state)
                "steps" -> if (state.step == JumpStep.WATER) next(state, JumpStep.GUESS, "steps_ack", "candidates", "guess_q") else describe(state)
                "otter" -> question(state, JumpGuess.OTTER)
                "penguin" -> question(state, JumpGuess.PENGUIN)
                "both" -> question(state, JumpGuess.BOTH)
                "outside" -> question(state, JumpGuess.OTHER)
                "fast" -> fastAnswer(state)
                "body" -> next(state, JumpStep.IDENTIFY, *if (state.step == JumpStep.WATER) arrayOf("candidates", "feather_evidence", "who_q") else arrayOf("feather_evidence", "who_q"))
                else -> describe(state)
            }
            JumpStep.QUESTION -> when (id) {
                "feathers","wings","body" -> next(state, JumpStep.IDENTIFY)
                "penguin", "otter" -> {
                    val nextGuess = if (id == "penguin") JumpGuess.PENGUIN else JumpGuess.OTTER
                    val ack = if (nextGuess == JumpGuess.PENGUIN) "guess_penguin" else "guess_otter"
                    next(state, JumpStep.IDENTIFY, ack, "hint_body", "feather_evidence", "who_q", guess = nextGuess)
                }
                "fast" -> fastAnswer(state)
                "swim" -> if (state.assisted) next(state, JumpStep.IDENTIFY, "weak_shared", "feather_evidence", "who_q")
                    else describe(state.copy(assisted = true), listOf("weak_shared"))
                else -> describe(state)
            }
            JumpStep.IDENTIFY -> when (id) {
                "penguin","fast" -> next(state, JumpStep.DIRECT)
                "otter","outside","both" -> hint(state)
                else -> describe(state)
            }
            JumpStep.DIRECT -> when (id) {
                "soft" -> finish(state, JumpEnding.SOFT)
                "bold" -> finish(state, JumpEnding.BOLD)
                else -> describe(state)
            }
            else -> describe(state)
        }
    }

    fun hear(state: JumpState, text: String?): JumpTurn {
        if (state.paused || state.step == JumpStep.READY || state.step == JumpStep.END) return resume(state)
        if (text.isNullOrBlank()) return noMatch(state, true)
        if (QuietRequest.matches(text)) return choose(state, "rest")
        val answer = JumpAnswers.match(state.step, text)
        return if (answer == null) noMatch(state, false) else choose(state, answer)
    }
}

/** Bounded whole-utterance matches avoid accepting "선물" as water or "펭귄은 아니야" as a guess. */
private object JumpAnswers {
    private val address = Regex("^오누(?:야|야아)[\\s,，.!?]+")
    private val ending = "(?:이다|이야|야|이요|이에요|예요|같아|같아요|일것같아|일거같아|일것같아요|아닐까|인것같아|인거같아|이라고생각해)?"
    private val penguin = Regex("(?:나는|난|내생각엔)?(?:펭귄|팽귄)$ending")
    private val otter = Regex("(?:나는|난|내생각엔)?해달$ending")
    private val other = Regex("(?:오리|물개|고래|수달|돌고래)(?:도헤엄(?:쳐|쳐요|칠수있어))?$ending")
    private val both = Regex("(?:둘다|두친구다|둘중하나|해달이나펭귄|펭귄이나해달)(?:야|이야|가능해|가능하지|같아|일수있어|헤엄(?:쳐|치잖아)|할수있어)?")
    private val water = Regex("(?:첨벙|첨벙소리|퐁당|물소리|물|물속|바다|강물|바닷물|물방울|물웅덩이)(?:속|에|으로|로|에서|이|가)?(?:들어갔어|뛰어들었어|뛰었어|빠졌어|떨어졌어|튀었어|났어|들렸어|첨벙했어|같아|야|이야|요)?")
    private val fall = setOf("뭔가떨어졌어", "뭔가떨어진소리", "떨어지는소리", "물방울이튀었어", "첨벙물에빠졌어", "첨벙하는소리", "첨벙하는소리야", "물방울이튀는소리야", "물에들어가는소리", "이건물에뛰어든소리같아", "물에뛰어든소리같아", "이건물소리같아")
    private val waterPhrases = setOf("나는물소리들었어", "물이첨벙하는소리")
    private val bothSounds = setOf("둘다", "둘다야", "둘다들렸어", "두소리다", "두소리다들렸어",
        "물소리랑발소리", "발소리랑물소리", "물소리도나고발소리도났어", "발소리도나고물소리도났어")
    private val steps = Regex("(?:발소리|발자국소리|타닥타닥|타다닥|타닥|걷는소리|바위에딛는소리)(?:가|가났어|가들려|가들렸어|들렸어|야|같아|요)?")
    private val body = Regex("(?:그친구|친구)?(?:몸에)?(?:깃털|기털|날개|털)(?:이|가|은|는)?(?:있어|있나|있니|있을까|있을지|있는지|있냐|있대|있대요|있겠지|있어서|있어보여|있잖아)?(?:물어보자|물어봐|물어보면어때|물어볼래)?")
    private val wings = Regex("(?:날개|지느러미처럼생긴날개)(?:로|를써서)?(?:헤엄쳐|헤엄치는지|헤엄칠수있어|헤엄칠수있니|수영해|수영하는지)(?:물어보자|물어봐|물어보면어때|물어볼래)?")
    private val swim = Regex("(?:너|친구)?(?:수영|헤엄)(?:을)?(?:잘)?(?:해|하니|해요|쳐|치니|치는지|하는지|할수있어|칠수있어)(?:물어봐)?")
    private val commands = mapOf("again" to setOf("다시", "다시듣기", "다시들려줘", "다시한번들려줘", "한번더", "또들려줘", "다시듣고싶어"),
        "hint" to setOf("힌트", "힌트줘", "힌트주세요", "도와줘", "모르겠어", "모르겠어요", "잘모르겠어", "어려워", "알려줘", "오누가말해줘"))
    private val gentle = setOf("살금살금", "살금살금크게첨벙", "살금살금그러다크게첨벙", "살금살금걷다가크게첨벙", "살금살금가다가크게첨벙", "크게첨벙", "큰첨벙", "첨벙", "살금살금가", "크게")
    private val bold = setOf("성큼성큼", "성큼성큼작게퐁당", "성큼성큼그러다작게퐁당", "성큼성큼걷다가작게퐁당", "성큼성큼가다가작게퐁당", "작게퐁당", "퐁당", "성큼성큼가", "작게")
    private val leadingEvidence = Regex("(?:몸에)?(?:(?:깃털|기털)(?:이)?(?:있으니까|있으니|있다면|있대|있어서|있잖아)|날개로헤엄(?:치니까|친대|쳐서))(.+)")
    private val earlyPenguin = Regex("(?:나는|난)?(?:펭귄|팽귄)(?:일것같아|같아|이야|인데|인가|일까|이겠지|이니까|은|이라면)?(.+)")

    fun match(step: JumpStep, text: String): String? {
        if (text.length > 100) return null
        val addressed = address.replaceFirst(Normalizer.normalize(text.trim(), Normalizer.Form.NFKC), "")
        val value = addressed.filter { it.isLetterOrDigit() }
        commands.entries.firstOrNull { value in it.value }?.let { return it.key }
        if (step == JumpStep.DIRECT) return when (value) {
            in gentle -> "soft"
            in bold -> "bold"
            else -> null
        }
        if (step == JumpStep.WATER && value in bothSounds) return "sounds"
        val afterEvidence = leadingEvidence.matchEntire(value)?.groupValues?.get(1)
        if (afterEvidence != null && penguin.matches(afterEvidence)) return "fast"
        val extra = earlyPenguin.matchEntire(value)?.groupValues?.get(1)
        if (extra != null && (body.matches(extra) || wings.matches(extra))) {
            // Fur belongs to the otter in this episode. Hear the evidence before confirming a guess.
            return if (extra.contains("깃털") || extra.contains("기털") || extra.contains("날개")) "fast" else "body"
        }
        if (step != JumpStep.DIRECT) {
            if (penguin.matches(value)) return "penguin"
            if (otter.matches(value)) return "otter"
            // Before the animals are introduced, an unnamed pair refers to the heard sounds.
            if (both.matches(value) && (step != JumpStep.WATER ||
                    (value.contains("해달") && value.contains("펭귄")))) return "both"
            if (other.matches(value)) return "outside"
            if (step == JumpStep.WATER && (water.matches(value) || value in fall || value in waterPhrases)) return "wet"
            if (step == JumpStep.WATER && steps.matches(value)) return "steps"
        }
        if (step == JumpStep.QUESTION) {
            if (wings.matches(value)) return "wings"
            if (body.matches(value) && (value.contains("깃털") || value.contains("기털"))) return "feathers"
        }
        if (step != JumpStep.IDENTIFY && (body.matches(value) || wings.matches(value) || value in setOf("새야", "새니", "새인지물어봐"))) return "body"
        if (step == JumpStep.QUESTION && swim.matches(value)) return "swim"
        return null
    }
}
