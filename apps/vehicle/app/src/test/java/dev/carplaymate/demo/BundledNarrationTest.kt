package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class BundledNarrationTest {
    private val phrase = "오누랑 상상 속 공룡 연구소에 가자."
    private fun audio(id: String = "voice") = BundledAudio(id, "story_audio/$id.wav", "a".repeat(64), 1000)
    private fun clip(text: String = phrase, cue: Boolean = false) = NarrationClip(text,
        BundledNarration.sha256(text.toByteArray(Charsets.UTF_8)), audio(), if (cue) audio("cue") else null)
    private fun spoken(parts: List<NarrationPart>) = parts.joinToString("") { when (it) {
        is NarrationPart.Text -> it.text
        is NarrationPart.Audio -> it.spokenText.orEmpty()
    } }

    @Test fun exactClipConsumesOnlyAuthoredTextAndPreservesNicknameAndQuestion() {
        val text = "Nova, $phrase 사라진 알을 물가에서 찾을까?"
        val parts = BundledNarration(listOf(clip(cue = true))).resolve(text)
        assertEquals(text, spoken(parts))
        assertEquals(4, parts.size)
        assertEquals("Nova, ", (parts[0] as NarrationPart.Text).text)
        assertNull((parts[1] as NarrationPart.Audio).spokenText)
        assertEquals(phrase, (parts[2] as NarrationPart.Audio).spokenText)
    }

    @Test fun unknownPunctuationSpacingAndTextRemainUntouched() {
        listOf("$phrase!", "오누랑  상상 속 공룡 연구소에 가자.", "오늘 3.5km를 가? 좋아!", "").forEach {
            val parts = BundledNarration(listOf(clip())).resolve(it)
            assertEquals(listOf(NarrationPart.Text(it)), parts)
        }
    }

    @Test fun longestExactPrefixWinsWithoutDuplicatedWords() {
        val longer = "$phrase 준비됐니?"
        val parts = BundledNarration(listOf(clip(), clip(longer).copy(audio = audio("long")))).resolve(longer)
        assertEquals(1, parts.size)
        assertEquals("long", (parts.single() as NarrationPart.Audio).audio.id)
        assertEquals(longer, spoken(parts))
    }

    @Test fun repeatedKnownTextPreservesEveryOccurrenceAndSpaces() {
        val source = "$phrase\n\n$phrase  끝!"
        val parts = BundledNarration(listOf(clip())).resolve(source)
        assertEquals(source, spoken(parts))
        assertEquals(2, parts.count { it is NarrationPart.Audio })
    }

    @Test fun emptyLibraryIsExactFallback() {
        val source = "  Nova 차례야.\n원문을 그대로 둘게. "
        assertEquals(listOf(NarrationPart.Text(source)), BundledNarration.EMPTY.resolve(source))
    }

    @Test fun hashMismatchAndDuplicateTextAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BundledNarration(listOf(clip().copy(textSha256 = "0".repeat(64)))) }
        assertThrows(IllegalArgumentException::class.java) { BundledNarration(listOf(clip(), clip())) }
    }

    @Test fun outsidePathsAndLongCuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BundledNarration(listOf(clip().copy(audio = audio().copy(asset = "../voice.wav")))) }
        assertThrows(IllegalArgumentException::class.java) { BundledNarration(listOf(clip().copy(cueBefore = audio("cue").copy(durationMillis = 3001)))) }
    }

    @Test fun partialWordsNeverBecomeClips() {
        val source = "앞${phrase}뒤"
        assertEquals(listOf(NarrationPart.Text(source)), BundledNarration(listOf(clip())).resolve(source))
    }
}
