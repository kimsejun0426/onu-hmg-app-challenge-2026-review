package dev.carplaymate.demo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** App-owned timing only; it does not establish when a provider releases its physical microphone. */
internal interface CaptureTimer {
    fun nowMillis(): Long
    fun postDelayed(callback: Runnable, delayMillis: Long)
    fun remove(callback: Runnable)
}

internal class MainThreadCaptureTimer : CaptureTimer {
    private val handler = Handler(Looper.getMainLooper())
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()
    override fun postDelayed(callback: Runnable, delayMillis: Long) {
        handler.postDelayed(callback, delayMillis)
    }
    override fun remove(callback: Runnable) {
        handler.removeCallbacks(callback)
    }
}

/**
 * One fixed budget from open() entry, including initialization and final-result waiting.
 * 30 seconds is an initial product limit, not a validated optimum for children's speech.
 * Stages cannot reset it. State-changing callbacks also check elapsed real time, so a delayed main loop
 * cannot accept a result after the budget. Expiry requests cleanup; it cannot preempt a blocking
 * platform/SDK call or guarantee that physical release finishes within this budget.
 */
internal class CaptureDeadline(
    private val startedAtMillis: Long,
    private val timer: CaptureTimer,
    private val onExpired: () -> Unit,
) {
    companion object {
        const val TOTAL_TIMEOUT_MILLIS = 30_000L
    }

    private var active = true
    private var scheduled = false
    private val callback = Runnable {
        scheduled = false
        if (accepting()) scheduleRemaining()
    }

    fun start(): Boolean {
        if (!accepting()) return false
        if (!scheduled) scheduleRemaining()
        return true
    }

    fun accepting(): Boolean {
        if (!active) return false
        if (remainingMillis() > 0L) return true
        cancel() // Invalidate before expiry can synchronously close or open another request.
        onExpired()
        return false
    }

    fun cancel() {
        active = false
        scheduled = false
        timer.remove(callback)
    }

    private fun remainingMillis(): Long =
        TOTAL_TIMEOUT_MILLIS - (timer.nowMillis() - startedAtMillis)

    private fun scheduleRemaining() {
        scheduled = true
        timer.postDelayed(callback, remainingMillis().coerceAtLeast(0L))
    }
}
