package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class MediaPauseLeaseTest {
    private val paused = MediaPausedSession("already-started", 9L, 4L, 12L)
    private val deadline = 5_000L + MediaPauseLease.DURATION_MILLIS
    private fun lease(token: String = "first-pause") =
        MediaPauseLease(token, paused.sessionId, paused.stopGeneration, deadline)

    @Test fun anUnconfirmedPauseCannotAuthorizeResume() {
        assertFalse(lease().permits("first-pause", paused, 5_001L))
        val bound = requireNotNull(lease().bind(paused, 5_001L))
        assertTrue(bound.permits("first-pause", paused, 5_002L))
    }

    @Test fun aDifferentSessionOrNewStopGenerationCannotBind() {
        assertNull(lease().bind(paused.copy(sessionId = "saved-checkpoint"), 5_001L))
        assertNull(lease().bind(paused.copy(stopGeneration = 13L), 5_001L))
    }

    @Test fun resumeNeedsTheExactConfirmedVersionEpochAndLocalGeneration() {
        val bound = requireNotNull(lease().bind(paused, 5_001L))
        listOf(paused.copy(sessionId = "another-story"), paused.copy(version = 10L),
            paused.copy(turnEpoch = 5L), paused.copy(stopGeneration = 13L)).forEach {
            assertFalse(bound.permits("first-pause", it, 5_002L))
        }
        assertTrue(bound.permits("first-pause", paused, 5_002L))
    }

    @Test fun repeatedPauseBindingNeverExtendsTheOriginalDeadline() {
        val bound = requireNotNull(lease().bind(paused, 5_001L))
        val repeated = requireNotNull(bound.bind(paused, deadline - 1L))
        assertSame(bound, repeated)
        assertEquals(deadline, repeated.expiresAtElapsed)
        assertTrue(repeated.permits("first-pause", paused, deadline - 1L))
        assertFalse(repeated.permits("first-pause", paused, deadline))
        assertFalse(repeated.permits("first-pause", paused, deadline + 1L))
    }

    @Test fun anExpiredLeaseCannotBindEvenAConfirmedPausedTurn() {
        assertEquals(120_000L, MediaPauseLease.DURATION_MILLIS)
        assertNull(lease().bind(paused, deadline))
        assertNull(lease().bind(paused, deadline + 1L))
    }

    @Test fun firstConfirmedTurnCannotBeReplacedWithANewerSnapshot() {
        val bound = requireNotNull(lease().bind(paused, 5_001L))
        assertNull(bound.bind(paused.copy(version = 10L), 5_002L))
        assertNull(bound.bind(paused.copy(turnEpoch = 5L), 5_002L))
        assertEquals(paused, bound.paused)
    }

    @Test fun oldOrInventedTokensCannotResumeANewLease() {
        val bound = requireNotNull(lease("new-pause").bind(paused, 5_001L))
        assertFalse(bound.permits("first-pause", paused, 5_002L))
        assertFalse(bound.permits("", paused, 5_002L))
        assertTrue(bound.permits("new-pause", paused, 5_002L))
    }
}
