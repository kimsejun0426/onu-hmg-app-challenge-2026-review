package dev.carplaymate.demo

import android.content.Context

/** Small recovery checkpoint, never an utterance/token store. Invalid reads fail closed. */
class LocalCheckpoint(context: Context, storageName: String = "recovery_v1") {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    val sessionId: String?
        get() = read { prefs.getString("session_id", null)?.also { require(it.length in 1..128) } }

    val pendingCommandIds: Set<String>
        get() = read {
            (prefs.getStringSet("pending_command_ids", emptySet())?.toSet() ?: emptySet()).also {
                ids ->
                require(ids.all { it.length in 1..128 })
            }
        }

    // Always force a quiet process start, even if the last durable write was interrupted.
    fun forceQuietOnRestore(): Boolean = quiet()

    fun quiet(): Boolean = write { prefs.edit().putBoolean("quiet", true).commit() }

    fun permit(): Boolean = write { prefs.edit().putBoolean("quiet", false).commit() }

    fun session(id: String): Boolean = write {
        require(id.length in 1..128)
        prefs.edit().putString("session_id", id).commit()
    }

    fun addPending(id: String): Boolean = write {
        require(id.length in 1..128)
        prefs.edit().putStringSet("pending_command_ids", pendingCommandIds + id).commit()
    }

    fun removePending(id: String): Boolean = write {
        prefs.edit().putStringSet("pending_command_ids", pendingCommandIds - id).commit()
    }

    fun clearPending(): Boolean = write { prefs.edit().remove("pending_command_ids").commit() }

    fun clear(): Boolean = write { prefs.edit().clear().commit() }

    private fun <T> read(block: () -> T): T =
        try {
            block()
        } catch (_: Exception) {
            throw ApiException(500, "LOCAL_CHECKPOINT_UNAVAILABLE")
        }

    private fun write(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (_: Exception) {
            false
        }
}
