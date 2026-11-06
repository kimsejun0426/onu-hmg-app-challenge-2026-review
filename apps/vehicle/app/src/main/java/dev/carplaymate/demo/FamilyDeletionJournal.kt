package dev.carplaymate.demo

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * A content-free tombstone; an interrupted multi-file deletion is resumed before loading a family.
 */
internal class FamilyDeletionJournal(context: Context) {
    private val marker = File(context.noBackupFilesDir, "family_deletion_pending")
    private val atomic = AtomicFile(marker)
    val pending: Boolean
        get() =
            listOf(marker, File(marker.path + ".bak"), File(marker.path + ".new")).any {
                it.exists()
            }

    fun begin(): Boolean {
        if (pending) return true
        return runCatching {
                val output = atomic.startWrite()
                try {
                    output.write(byteArrayOf(1))
                    output.fd.sync()
                    atomic.finishWrite(output)
                    check(atomic.readFully().contentEquals(byteArrayOf(1)))
                } catch (error: Exception) {
                    atomic.failWrite(output)
                    throw error
                }
            }
            .isSuccess
    }

    fun complete(): Boolean {
        atomic.delete()
        return !pending
    }
}

internal enum class FamilyDeletionStep {
    BEFORE_GAME,
    AFTER_GAME,
    AFTER_CHECKPOINTS,
    AFTER_PROFILE,
}
