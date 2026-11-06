package dev.carplaymate.demo

import java.text.Normalizer
import java.util.Locale

/** Exact, owner-independent stop aliases, matching the server's PAUSE vocabulary. */
object QuietRequest {
    private val aliases = setOf("그만", "그만해", "그만해줘", "그만할래", "그만하자", "이제그만",
        "멈춰", "멈춰줘", "조용히", "조용히해줘", "쉬자", "쉬고싶어", "쉴래", "졸려",
        "잠깐", "잠깐만", "일시정지", "하기싫어", "안할래", "안하고싶어", "그만하고싶어", "싫어", "싫어요", "오누야그만")
    // Match LocalGameEngine's bounded address grammar, only inside a captured answer.
    private val address = Regex("^오누야(?=$|[$PYTHON_SPACE_CHARS,:：.!?])[$PYTHON_SPACE_CHARS,:：.!?]*")
    private val repeatedAddress = Regex("(?<![\\p{L}\\p{N}_])오누야(?=$|[$PYTHON_SPACE_CHARS,:：.!?])")
    private const val quotedDelimiters = "\"'`“”‘’「」『』«»‹›()[]{}〈〉《》"

    /** Keep profile validation independent of spoken address grammar and existing names readable. */
    fun isReservedNickname(name: String): Boolean = compact(normalize(name)) in aliases

    fun matches(text: String, nicknames: List<String> = emptyList()): Boolean {
        val names = nicknames.sortedByDescending { it.codePointCount(0, it.length) }.map(::normalize)
        // A registered full name wins before treating 오누야 as an address.
        val declaration = stripNickname(pythonTrim(normalize(text)), names)
        var body = declaration.second
        if (names.none { compact(it) in setOf("오누", "오누야") }) {
            val match = address.find(body)
            if (match != null) {
                body = pythonTrim(body.substring(match.range.last + 1))
                if (!declaration.first) body = stripNickname(body, names).second
                // Never erase a quotation or another address to manufacture a stop.
                if (body.any { it in quotedDelimiters } || repeatedAddress.containsMatchIn(body)) return false
            }
        }
        return compact(body) in aliases
    }

    private fun stripNickname(text: String, names: List<String>): Pair<Boolean, String> {
        for (name in names) {
            if (name.isBlank()) continue
            val prefix = Regex("^${Regex.escape(name)}(?:(?:이가|이는|이|가|은|는))?(?=$|[$PYTHON_SPACE_CHARS,:：.!?])[$PYTHON_SPACE_CHARS]*[,：:]?[$PYTHON_SPACE_CHARS]*")
            val match = prefix.find(text) ?: continue
            return true to pythonTrim(text.substring(match.range.last + 1))
        }
        return false to text
    }

    private fun compact(value: String) = value.filter { it.isLetterOrDigit() || it == '_' }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
