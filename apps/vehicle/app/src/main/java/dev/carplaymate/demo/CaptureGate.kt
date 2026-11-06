package dev.carplaymate.demo

/** The server window and local output generation at the instant a capture starts. */
data class CaptureContext(
    val sessionId: String,
    val version: Long,
    val turnId: String,
    val turnEpoch: Long,
    val listenWindowId: String,
    val outputGeneration: Long,
)

data class CaptureTicket(val generation: Long, val context: CaptureContext)

/** Main-thread authority for one final result or error from one listening attempt. */
class CaptureGate {
    private var generation = 0L
    var active: CaptureTicket? = null
        private set

    fun open(context: CaptureContext): CaptureTicket {
        generation += 1
        return CaptureTicket(generation, context).also { active = it }
    }

    fun close() {
        generation += 1
        active = null
    }

    fun accepts(ticket: CaptureTicket, current: CaptureContext?): Boolean =
        active == ticket && ticket.context == current

    fun consume(ticket: CaptureTicket, current: CaptureContext?): Boolean {
        if (!accepts(ticket, current)) return false
        active = null
        return true
    }
}
