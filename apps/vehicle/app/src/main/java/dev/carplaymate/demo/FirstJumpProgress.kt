package dev.carplaymate.demo

import android.content.Context
import android.util.Base64

/** One small checkpoint lives in the same preference file as the family that owns it. */
internal class FirstJumpProgress(context: Context, private val episodeId: String = "first_jump") {
    init { require(episodeId in setOf("first_jump", "rain_music")) }
    private val record = "${episodeId}_progress_v1"
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences("family_v1", Context.MODE_PRIVATE)

    fun read(family: String): String? {
        if (!validFamily(family)) return null
        return try {
            val store = prefs
            val sealed = synchronized(store) {
                if (!isCurrentFamily(family)) return null
                store.getString(record, null)
            } ?: return null
            if (sealed.length > 4096) return null
            val cipher = PrivateRecordCipher(app, "${episodeId}_v1:$family")
            val state = cipher.decrypt(Base64.decode(sealed, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            state.takeIf(::validState)
        } catch (_: Exception) {
            null
        }
    }

    /** Seed a newly delivered reply once; another window or an earlier trip may already own progress. */
    fun initialize(family: String, state: String): String = try {
        synchronized(prefs) { read(family) ?: state.also { save(family, it) } }
    } catch (_: Exception) { state }

    fun save(family: String, state: String) {
        if (!validFamily(family) || !validState(state)) return
        try {
            val store = prefs
            val profile = synchronized(store) {
                if (!isCurrentFamily(family)) return
                store.getString("profile_v3", null)
            } ?: return
            val cipher = PrivateRecordCipher(app, "${episodeId}_v1:$family")
            val bytes = cipher.encryptExisting(state.toByteArray(Charsets.UTF_8))
            // FamilyPreferences uses this same lock for replacement and deletion. A late
            // activity callback cannot resurrect the old checkpoint after either operation.
            synchronized(store) {
                if (store.getString("profile_v3", null) == profile) {
                    store.edit().putString(record, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
                }
            }
        } catch (_: Exception) {
            // The current round remains playable if private storage becomes unavailable.
        }
    }

    private fun isCurrentFamily(family: String): Boolean {
        val current = FamilyPreferences(app).profile ?: return false
        return current.children.joinToString(",") { it.id } == family
    }

    private fun validFamily(family: String): Boolean {
        val ids = family.split(',')
        return ids.size in 1..2 && ids.all(PROFILE_ID::matches) && ids.distinct().size == ids.size
    }

    private fun validState(text: String): Boolean {
        if (text.length !in 1..100) return false
        if (episodeId == "rain_music") return restoreRain(text)?.rainCheckpoint() == text
        val state = restoreLetter(text) ?: return false
        return state.letterCheckpoint() == text &&
            (state.step == JumpStep.END) == (state.ending != null) &&
            !(state.paused && state.step in setOf(JumpStep.READY, JumpStep.END))
    }

    private companion object {
        val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
