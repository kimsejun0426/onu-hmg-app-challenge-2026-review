package dev.carplaymate.demo

import android.content.Context
import java.io.ByteArrayOutputStream
import org.json.JSONObject

internal data class NarrationAssets(
    val narration: BundledNarration,
    val failure: SpeechOutputFailure? = null,
)

internal fun loadNarrationAssets(context: Context): NarrationAssets {
    val failure = SpeechOutputFailure("이야기 음원을 확인하지 못해 조용히 멈췄어요.", RecoveryCause.OUTPUT_FAILED)
    return try {
        val assets = context.assets
        if (assets.list("story_audio")?.contains("manifest.json") != true) {
            NarrationAssets(BundledNarration.EMPTY)
        } else {
            val bytes = assets.open("story_audio/manifest.json").use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 256 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val document = JSONObject(bytes.toString(Charsets.UTF_8))
            require(document.getInt("schema_version") == 1)
            fun audio(value: JSONObject) = BundledAudio(value.getString("id"), value.getString("asset"),
                value.getString("sha256"), value.getLong("duration_ms"))
            val cuesJson = document.getJSONArray("cues")
            require(cuesJson.length() <= 64)
            val cuesList = List(cuesJson.length()) { audio(cuesJson.getJSONObject(it)) }
            require(cuesList.map { it.id }.distinct().size == cuesList.size)
            cuesList.forEach { BundledNarration.validateAudio(it, 3_000L) }
            val cues = cuesList.associateBy { it.id }
            val clipsJson = document.getJSONArray("clips")
            require(clipsJson.length() <= 256)
            val clips = List(clipsJson.length()) { index ->
                val value = clipsJson.getJSONObject(index)
                val cueId = value.optString("cue_before").takeIf { it.isNotBlank() && it != "null" }
                NarrationClip(value.getString("text"), value.getString("text_sha256"), audio(value),
                    cueId?.let { checkNotNull(cues[it]) })
            }
            require(clips.map { it.audio.id }.distinct().size == clips.size)
            NarrationAssets(BundledNarration(clips))
        }
    } catch (_: Exception) {
        // Neither raw exception messages nor authored/child text is written to logs.
        NarrationAssets(BundledNarration.EMPTY, failure)
    }
}
