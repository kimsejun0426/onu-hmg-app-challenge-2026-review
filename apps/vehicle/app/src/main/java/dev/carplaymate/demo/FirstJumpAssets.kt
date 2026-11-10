package dev.carplaymate.demo

import android.content.Context
import org.json.JSONObject

/** Only app-authored audio can enter the letter's player. No child audio enters this catalog. */
internal class FirstJumpAssets(context: Context, private val episodeId: String = "first_jump") {
    init { require(episodeId in setOf("first_jump", "rain_music")) }
    private val assets = context.applicationContext.assets
    private data class TimedVoice(val hash: String, val window: AudioSpeechWindow)

    private fun windows(root: String): Map<String, TimedVoice> {
        if (episodeId != "rain_music") return emptyMap()
        return runCatching {
            val doc = assets.open("$root/narration_windows.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            require(doc.getInt("schema_version") == 1)
            val rows = doc.getJSONArray("clips")
            require(rows.length() == 2)
            buildMap {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val id = row.getString("id")
                    require(id == "finale_drops" || id == "finale_wiper")
                    val window = AudioSpeechWindow(row.getLong("start_ms"), row.getLong("end_ms"))
                    require(window.startMillis >= 0 && window.startMillis < window.endMillis)
                    require(put(id, TimedVoice(row.getString("sha256"), window)) == null)
                }
            }
        }.getOrElse { emptyMap() }
    }

    private val clips: Map<String, BundledAudio> by lazy {
        val root = "sound_letters/$episodeId"
        val timed = windows(root)
        val document = assets.open("$root/manifest.json").bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }
        val entries = document.getJSONArray("clips")
        require(entries.length() in 1..96)
        buildMap {
            for (i in 0 until entries.length()) {
                val row = entries.getJSONObject(i)
                val id = row.getString("id")
                require(id.matches(Regex("[a-z0-9_]{1,80}")))
                val path = row.getString("asset")
                require(path == "$root/audio/$id.mp3")
                val hash = row.getString("sha256")
                require(hash.matches(Regex("[0-9a-f]{64}")))
                val duration = row.getLong("duration_ms")
                require(duration in 1..60_000L)
                val window = timed[id]?.takeIf { it.hash == hash && it.window.endMillis <= duration }?.window
                require(put(id, BundledAudio(id, path, hash, duration, window)) == null)
            }
        }
    }
    fun audio(id: String): BundledAudio? = clips[id]
}
