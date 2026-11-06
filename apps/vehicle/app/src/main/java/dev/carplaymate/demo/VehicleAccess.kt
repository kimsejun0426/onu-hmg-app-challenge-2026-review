package dev.carplaymate.demo

/** UNRESTRICTED describes platform UXR for this display, not a measured gear/parking state. */
enum class VehicleAccessState {
    NON_AUTOMOTIVE_DEMO,
    UNKNOWN,
    RESTRICTED,
    UNRESTRICTED;

    val allowsInteraction: Boolean
        get() = this == NON_AUTOMOTIVE_DEMO || this == UNRESTRICTED
}

/** Raw platform values for the observed display; these are not permission to run voice. */
data class VehicleUxRestrictions(
    val requiresDistractionOptimization: Boolean,
    val activeRestrictions: Int,
    val maxStringLength: Int,
    val maxCumulativeContentItems: Int,
    val maxContentDepth: Int,
)

data class VehicleUxSnapshot(
    val access: VehicleAccessState,
    val restrictions: VehicleUxRestrictions? = null,
)

/** Serial callback reducer. A snapshot query must never overwrite a newer listener event. */
internal class VehicleRestrictionGate(
    private val changed: (VehicleAccessState) -> Unit,
    private val snapshotChanged: (VehicleUxSnapshot) -> Unit,
) {
    // Keep the existing one-callback and trailing-lambda construction source-compatible.
    constructor(changed: (VehicleAccessState) -> Unit) : this(changed, {})

    data class Query(val connection: Long, val revision: Long)

    private var connection = 0L
    private var revision = 0L
    var state = VehicleAccessState.UNKNOWN
        private set
    var snapshot = VehicleUxSnapshot(VehicleAccessState.UNKNOWN)
        private set

    fun newConnection(): Long {
        connection++
        revision = 0L
        publish(VehicleUxSnapshot(VehicleAccessState.UNKNOWN))
        return connection
    }

    fun query(connection: Long): Query? =
        if (connection == this.connection) Query(connection, revision) else null

    fun event(connection: Long, requiresOptimization: Boolean?) {
        if (connection != this.connection) return
        revision++
        publish(VehicleUxSnapshot(map(requiresOptimization)))
    }

    fun result(query: Query, requiresOptimization: Boolean?) {
        if (query.connection != connection || query.revision != revision) return
        revision++
        publish(VehicleUxSnapshot(map(requiresOptimization)))
    }

    fun eventRestrictions(connection: Long, restrictions: VehicleUxRestrictions?) {
        if (connection != this.connection) return
        revision++
        publish(snapshotOf(restrictions))
    }

    fun resultRestrictions(query: Query, restrictions: VehicleUxRestrictions?) {
        if (query.connection != connection || query.revision != revision) return
        revision++
        publish(snapshotOf(restrictions))
    }

    fun disconnect() {
        newConnection()
    }

    private fun publish(next: VehicleUxSnapshot) {
        state = next.access
        snapshot = next
        // Do not conflate brief restrictions or changed flags/limits with the same access state.
        changed(next.access)
        snapshotChanged(next)
    }

    private fun snapshotOf(restrictions: VehicleUxRestrictions?) =
        VehicleUxSnapshot(map(restrictions?.requiresDistractionOptimization), restrictions)

    private fun map(requiresOptimization: Boolean?) =
        when (requiresOptimization) {
            null -> VehicleAccessState.UNKNOWN
            true -> VehicleAccessState.RESTRICTED
            false -> VehicleAccessState.UNRESTRICTED
        }
}
