package dev.carplaymate.demo

/** Pure local authority for audio. A network response can never reopen this gate. */
class OutputGate {
    var generation: Long = 0L
        private set
    var quiet: Boolean = true
        private set

    fun stop(): Long {
        quiet = true
        generation += 1
        return generation
    }

    /** Only an explicit new-session/resume action may call this. */
    fun permitExplicitly(): Long {
        quiet = false
        generation += 1
        return generation
    }

    fun replacePlan(): Long {
        check(!quiet)
        generation += 1
        return generation
    }

    fun accepts(token: Long): Boolean = !quiet && token == generation
    fun isCurrent(token: Long): Boolean = token == generation
}
