package dev.carplaymate.demo

import org.junit.Assert.*
import org.junit.Test

class CaptureDeadlineTest {
    @Test fun repeatedChecksAndStartDoNotRenewTheOriginalBudget() {
        val timer = FakeTimer()
        var expired = 0
        val deadline = CaptureDeadline(timer.nowMillis(), timer) { expired++ }
        assertTrue(deadline.start())
        for (time in listOf(9_000L, 19_000L, 29_999L)) {
            timer.now = time
            assertTrue(deadline.accepting())
            assertTrue(deadline.start())
        }
        assertEquals(1, timer.pending.size)
        assertEquals(30_000L, timer.pending.single().second)
        timer.now = 30_000L
        timer.pending.single().first.run()
        assertEquals(1, expired)
        assertFalse(deadline.accepting())
        assertFalse(deadline.start())
        assertTrue(timer.pending.isEmpty())
    }

    @Test fun cancelMakesEvenAnAlreadyQueuedCallbackHarmless() {
        val timer = FakeTimer()
        var expired = 0
        val deadline = CaptureDeadline(0L, timer) { expired++ }
        deadline.start()
        val oldCallback = timer.pending.single().first
        deadline.cancel()
        timer.now = 50_000L
        oldCallback.run()
        assertEquals(0, expired)
        assertFalse(deadline.accepting())
        assertTrue(timer.pending.isEmpty())
    }

    @Test fun oldCallbackCannotExpireTheNextRequest() {
        val timer = FakeTimer()
        var oldExpired = 0
        var newExpired = 0
        val old = CaptureDeadline(0L, timer) { oldExpired++ }
        old.start()
        val oldCallback = timer.pending.single().first
        old.cancel()
        timer.now = 29_000L
        val next = CaptureDeadline(timer.nowMillis(), timer) { newExpired++ }
        next.start()
        timer.now = 30_000L
        oldCallback.run()
        assertTrue(next.accepting())
        assertEquals(0, oldExpired)
        assertEquals(0, newExpired)
        assertEquals(59_000L, timer.pending.single().second)
    }

    @Test fun elapsedTimeRejectsLateResultBeforeDelayedTimerRuns() {
        val timer = FakeTimer()
        var expired = 0
        val deadline = CaptureDeadline(0L, timer) { expired++ }
        deadline.start()
        val callback = timer.pending.single().first
        timer.now = 30_001L // Simulates delayed dispatch / time spent suspended.
        assertFalse(deadline.accepting())
        callback.run()
        assertEquals(1, expired)
        assertTrue(timer.pending.isEmpty())
    }

    @Test fun setupTimeAlreadyConsumedCannotStartANewThirtySecondBudget() {
        val timer = FakeTimer().apply { now = 30_000L }
        var expired = 0
        val deadline = CaptureDeadline(0L, timer) { expired++ }
        assertFalse(deadline.start())
        assertEquals(1, expired)
        assertTrue(timer.pending.isEmpty())
    }

    private class FakeTimer : CaptureTimer {
        var now = 0L
        val pending = mutableListOf<Pair<Runnable, Long>>()
        override fun nowMillis() = now
        override fun postDelayed(callback: Runnable, delayMillis: Long) {
            pending.add(callback to (now + delayMillis))
        }
        override fun remove(callback: Runnable) {
            pending.removeAll { it.first === callback }
        }
    }
}
