package dev.carplaymate.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/** Screen-owned permission results. Waiting never grants vehicle access or starts audio. */
internal class FamilyPermissionReturns private constructor(initial: State) {
    constructor() : this(State())

    enum class Kind { VOICE, OUTPUT, SETTINGS }
    data class Result(val kind: Kind, val granted: Boolean, val token: String)

    private data class State(
        val generation: Long = 0,
        val family: String? = null,
        val inFlight: Map<Kind, String> = emptyMap(),
        val pending: Result? = null,
    )

    private var state by mutableStateOf(initial)
    val pendingToken: String? get() = state.pending?.token

    fun synchronize(family: String?) {
        if (state.family != family) invalidate(family)
    }

    /** Keep stale launch slots until their own result returns; never relabel an old OS result. */
    fun invalidate(family: String?) {
        state = state.copy(generation = state.generation + 1, family = family, pending = null)
    }

    fun begin(kind: Kind, family: String?): Boolean {
        invalidate(family)
        if (family == null || kind in state.inFlight) return false
        state = state.copy(inFlight = state.inFlight + (kind to currentToken()))
        return true
    }

    fun receive(kind: Kind, granted: Boolean, family: String?) {
        synchronize(family)
        val token = state.inFlight[kind] ?: return
        state = state.copy(inFlight = state.inFlight - kind)
        if (family != null && token == currentToken())
            state = state.copy(pending = Result(kind, granted, token))
    }

    fun cancel(kind: Kind) {
        state = state.copy(inFlight = state.inFlight - kind)
    }

    /** Consume before invoking the VM, so recomposition/lifecycle events cannot repeat the effect. */
    fun take(family: String?, canApply: Boolean): Result? {
        synchronize(family)
        val result = state.pending ?: return null
        if (family == null || result.token != currentToken()) {
            state = state.copy(pending = null)
            return null
        }
        if (!canApply) return null
        state = state.copy(pending = null)
        return result
    }

    private fun currentToken() = "${state.generation}:${state.family}"

    companion object {
        // Only request identity and grant booleans survive recreation, never utterances or service credentials.
        val Saver = listSaver<FamilyPermissionReturns, Any>(
            save = {
                val s = it.state
                listOf(s.generation, s.family.orEmpty(),
                    s.inFlight[Kind.VOICE].orEmpty(), s.inFlight[Kind.OUTPUT].orEmpty(),
                    s.inFlight[Kind.SETTINGS].orEmpty(), s.pending?.token.orEmpty(),
                    s.pending?.kind?.name.orEmpty(), s.pending?.granted ?: false)
            },
            restore = { values ->
                runCatching {
                    val slots = listOf(Kind.VOICE, Kind.OUTPUT, Kind.SETTINGS)
                        .mapIndexedNotNull { index, kind ->
                            (values[index + 2] as String).takeIf { it.isNotEmpty() }?.let { kind to it }
                        }.toMap()
                    val pending = (values[5] as String).takeIf { it.isNotEmpty() }?.let {
                        Result(Kind.valueOf(values[6] as String), values[7] as Boolean, it)
                    }
                    FamilyPermissionReturns(State(values[0] as Long,
                        (values[1] as String).takeIf { it.isNotEmpty() }, slots, pending))
                }.getOrElse { FamilyPermissionReturns() }
            },
        )
    }
}
