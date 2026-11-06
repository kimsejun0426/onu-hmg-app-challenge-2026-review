package dev.carplaymate.demo

import java.security.MessageDigest

internal data class BundledAudio(
    val id: String,
    val asset: String,
    val sha256: String,
    val durationMillis: Long,
)

internal data class NarrationClip(
    val text: String,
    val textSha256: String,
    val audio: BundledAudio,
    val cueBefore: BundledAudio? = null,
)

internal sealed interface NarrationPart {
    data class Text(val text: String) : NarrationPart
    data class Audio(val audio: BundledAudio, val spokenText: String?) : NarrationPart
}

/** Exact authored text only. No inferred emotion, child recording, or sentence rewriting. */
internal class BundledNarration(private val clips: List<NarrationClip>) {
    init {
        require(clips.size <= 256)
        require(clips.map { it.text }.distinct().size == clips.size)
        clips.forEach {
            require(it.text.length in 8..4000 && it.text == it.text.trim())
            require(it.text.last() in ".!?。！？")
            require(sha256(it.text.toByteArray(Charsets.UTF_8)) == it.textSha256)
            validateAudio(it.audio, 60_000L)
            it.cueBefore?.let { cue -> validateAudio(cue, 3_000L) }
        }
    }

    val isEmpty: Boolean get() = clips.isEmpty()

    fun resolve(text: String): List<NarrationPart> {
        if (clips.isEmpty()) return listOf(NarrationPart.Text(text))
        val result = mutableListOf<NarrationPart>()
        var cursor = 0
        var pendingText = 0
        // Consume exact known prefixes at each position; never regex-split an unknown sentence.
        while (cursor < text.length) {
            val match = clips.asSequence().filter { clip ->
                text.startsWith(clip.text, cursor) &&
                    (cursor == 0 || text[cursor - 1].isWhitespace()) &&
                    (cursor + clip.text.length == text.length || text[cursor + clip.text.length].isWhitespace())
            }.maxByOrNull { it.text.length }
            if (match == null) { cursor++; continue }
            if (cursor > pendingText) result += NarrationPart.Text(text.substring(pendingText, cursor))
            match.cueBefore?.let { result += NarrationPart.Audio(it, null) }
            result += NarrationPart.Audio(match.audio, match.text)
            cursor += match.text.length
            pendingText = cursor
        }
        if (pendingText < text.length) result += NarrationPart.Text(text.substring(pendingText))
        return result.ifEmpty { listOf(NarrationPart.Text(text)) }
    }

    companion object {
        val EMPTY = BundledNarration(emptyList())
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun validateAudio(audio: BundledAudio, maximum: Long) {
            require(audio.id.matches(Regex("[a-z0-9_]{1,80}")))
            require(audio.asset.matches(Regex("story_audio/[a-z0-9_]{1,80}\\.wav")))
            require(audio.sha256.matches(Regex("[0-9a-f]{64}")))
            require(audio.durationMillis in 1..maximum)
        }
    }
}
