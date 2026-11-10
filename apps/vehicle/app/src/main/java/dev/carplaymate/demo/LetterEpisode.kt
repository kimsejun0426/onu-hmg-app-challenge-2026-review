package dev.carplaymate.demo

/** Each letter owns its decisions. Both share the Android output, input and interruption rules. */
internal data class LetterTurn<S>(
    val state: S, val title: String, val text: String, val cues: List<String>,
    val choices: List<JumpChoice>, val acceptsAnswer: Boolean,
)

internal interface LetterEpisode<S> {
    val id: String
    val invitation: String
    val finale: String
    val effectStatus: String
    fun initial(): LetterTurn<S>
    fun resume(state: S): LetterTurn<S>
    fun choose(state: S, id: String): LetterTurn<S>
    fun hear(state: S, answer: String?): LetterTurn<S>
    fun isEffect(id: String): Boolean
    fun caption(id: String): String
    fun paused(state: S): Boolean
    fun stage(state: S): Int
    fun stepName(state: S): String
    fun encode(state: S): String
    fun decode(token: String?): S?
}

internal object JumpEpisode : LetterEpisode<JumpState> {
    override val id = "first_jump"
    override val invitation = "바다에서 온 소리 편지"
    override val finale = "펭귄의 첫 점프!"
    override val effectStatus = "점프 소리를 듣는 중"
    private fun JumpTurn.turn() = LetterTurn(state, title, text, cues, choices, acceptsAnswer)
    override fun initial() = FirstJumpGame.initial().turn()
    override fun resume(state: JumpState) = FirstJumpGame.resume(state).turn()
    override fun choose(state: JumpState, id: String) = FirstJumpGame.choose(state, id).turn()
    override fun hear(state: JumpState, answer: String?) = FirstJumpGame.hear(state, answer).turn()
    override fun isEffect(id: String) = id == "splash"
    override fun caption(id: String) = FirstJumpGame.caption(id)
    override fun paused(state: JumpState) = state.paused
    override fun stepName(state: JumpState) = state.step.name
    override fun encode(state: JumpState) = state.letterCheckpoint()
    override fun decode(token: String?) = restoreLetter(token)
    override fun stage(state: JumpState) = when (state.step) {
        JumpStep.READY -> 0
        JumpStep.WATER -> 1
        JumpStep.GUESS, JumpStep.QUESTION, JumpStep.IDENTIFY -> 2
        JumpStep.DIRECT -> 3
        JumpStep.END -> 4
    }
}

internal class RainEpisode(private val previous: JumpEnding?) : LetterEpisode<RainState> {
    override val id = "rain_music"
    override val invitation = "펭귄에게서 온 답장"
    override val finale = "펭귄의 빗소리 방송"
    override val effectStatus = "소리를 듣는 중"
    private fun RainTurn.turn() = LetterTurn(state, title, text, cues, choices, acceptsAnswer)
    override fun initial() = RainGame.initial(previous).turn()
    override fun resume(state: RainState) = RainGame.resume(state).turn()
    override fun choose(state: RainState, id: String) = RainGame.choose(state, id).turn()
    override fun hear(state: RainState, answer: String?) = RainGame.hear(state, answer).turn()
    // The finales feature music with a brief voice at the end; they do not animate as continuous speech.
    override fun isEffect(id: String) = RainGame.isEffect(id) || id == "finale_drops" || id == "finale_wiper"
    override fun caption(id: String) = RainGame.caption(id)
    override fun paused(state: RainState) = state.paused
    override fun stepName(state: RainState) = state.step.name
    override fun encode(state: RainState) = state.rainCheckpoint()
    override fun decode(token: String?) = restoreRain(token)
    override fun stage(state: RainState) = when (state.step) {
        RainStep.READY -> 0
        RainStep.DROPS, RainStep.WIPER -> 1
        RainStep.TEMPO -> 2
        RainStep.DIRECT -> 3
        RainStep.END -> 4
    }
}
