package dev.carplaymate.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterMediaLeaseTest {
    private val paused = LetterTransportSnapshot(
        sessionId = "live-letter-before-pause",
        revision = 7L,
        phase = LetterTransportPhase.STOPPED,
    )
    private val pauseAt = 10_000L
    private val expiresAt = pauseAt + LetterMediaLease.DURATION_MS
    private val lease = LetterMediaLease("one-shot-control", paused.sessionId, paused.revision, expiresAt)

    @Test fun deadlineIsExclusiveAndRepeatedObservationDoesNotExtendThePause() {
        assertTrue(lease.matches(paused, pauseAt))
        assertTrue(lease.matches(paused, expiresAt - 1L))
        assertTrue(lease.matches(paused, expiresAt - 1L))
        assertFalse(lease.matches(paused, expiresAt))
        assertFalse(lease.matches(paused, expiresAt + 1L))
    }

    @Test fun aNewSessionCannotUseTheOldLeaseEvenAtTheSameRevision() {
        val recreated = paused.copy(sessionId = "recreated-letter")
        assertFalse(lease.matches(recreated, pauseAt + 1L))
        assertTrue(lease.matches(paused, pauseAt + 1L))
    }

    @Test fun resumedOrRevokedTurnCannotReuseThePreviousPauseRevision() {
        val revokedAgain = paused.copy(revision = paused.revision + 1L)
        assertFalse(lease.matches(revokedAgain, pauseAt + 1L))
        assertFalse(lease.matches(paused.copy(revision = paused.revision - 1L), pauseAt + 1L))
        assertTrue(lease.matches(paused, pauseAt + 1L))
    }

    @Test fun inputProcessingOutputAndErrorsAreNotAResumableStoppedTurn() {
        for (phase in listOf(LetterTransportPhase.INPUT, LetterTransportPhase.PROCESSING,
            LetterTransportPhase.PREPARING, LetterTransportPhase.PLAYING, LetterTransportPhase.ERROR)) {
            assertFalse(lease.matches(paused.copy(phase = phase), pauseAt + 1L))
        }
        assertTrue(lease.matches(paused, pauseAt + 1L))
    }
}
