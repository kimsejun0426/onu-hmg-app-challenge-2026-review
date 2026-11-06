package dev.carplaymate.demo

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** Preference only: restoring VOICE never grants microphone or playback permission. */
internal enum class InteractionMode { LISTEN_AND_SELECT, VOICE, READING }

internal data class InteractionPreference(
    val mode: InteractionMode = InteractionMode.LISTEN_AND_SELECT,
    val unavailable: Boolean = false,
    /** Derived from a valid existing record; not another persisted flag. */
    val configured: Boolean = false,
)

/** Same family store and deletion transaction; legacy profiles need no rewrite. */
internal class InteractionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("family_v1", Context.MODE_PRIVATE)
    private val cipher = PrivateRecordCipher(context, "interaction_mode_v1")

    @Synchronized
    fun read(): InteractionPreference = try {
        val raw = prefs.all[KEY]
        if (raw == null) InteractionPreference()
        else {
            require(raw is String && raw.length <= 4096)
            val document = JSONObject(String(cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP)), Charsets.UTF_8))
            require(document.keyList().toSet() == setOf("schema_version", "mode"))
            require(document.opt("schema_version") == 1)
            InteractionPreference(InteractionMode.valueOf(document.getString("mode")), configured = true)
        }
    } catch (_: Exception) {
        // Corruption/key loss must not silently become a different interaction mode.
        InteractionPreference(unavailable = true)
    }

    @Synchronized
    fun save(mode: InteractionMode): Boolean = try {
        if (read().unavailable) false
        else {
            val previous = prefs.getString(KEY, null)
            val document = JSONObject().put("schema_version", 1).put("mode", mode.name)
            val sealed = Base64.encodeToString(cipher.encrypt(document.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            val saved = prefs.edit().putString(KEY, sealed).commit()
            if (!saved) {
                // commit may update memory despite failing to persist. Restore the old value.
                val rollback = prefs.edit()
                if (previous == null) rollback.remove(KEY) else rollback.putString(KEY, previous)
                rollback.commit()
            }
            saved && prefs.getString(KEY, null) == sealed
        }
    } catch (_: Exception) { false }

    companion object { internal const val KEY = "interaction_v1" }
}
