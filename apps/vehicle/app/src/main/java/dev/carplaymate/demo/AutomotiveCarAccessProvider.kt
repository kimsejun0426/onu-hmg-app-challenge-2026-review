package dev.carplaymate.demo

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AAOS public SDK only; loaded behind FEATURE_AUTOMOTIVE. This class must stay a separate R8
 * boundary so ordinary Android never resolves android.car types. No speed/gear inference or DO
 * claim. API: developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager
 */
internal class AutomotiveCarAccessProvider : VehicleAccessProvider {
    override val initialState = VehicleAccessState.UNKNOWN
    private val handler = Handler(Looper.getMainLooper())
    private var lifetime = 0L
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var scope: CoroutineScope? = null
    private var gate: VehicleRestrictionGate? = null

    override fun start(context: Context, changed: (VehicleAccessState) -> Unit) {
        startObserving(context) { changed(it.access) }
    }

    override fun startObserving(context: Context, changed: (VehicleUxSnapshot) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        val epoch = ++lifetime
        val currentGate = VehicleRestrictionGate({}, changed).also { gate = it }
        val currentScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        currentGate.disconnect()
        try {
            // A zero timeout never waits for car service on the main thread. The lifecycle
            // listener can be synchronous, so initialize through its Car argument, not our field.
            car =
                Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready
                    ->
                    if (epoch != lifetime) return@createCar
                    val connection = currentGate.newConnection()
                    clearManager()
                    if (!ready) return@createCar
                    try {
                        val restrictions =
                            connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                                as? CarUxRestrictionsManager ?: return@createCar
                        manager = restrictions
                        restrictions.registerListener { value ->
                            if (epoch == lifetime) {
                                currentGate.eventRestrictions(
                                    connection,
                                    copyRestrictions(value),
                                )
                            }
                        }
                        val query = currentGate.query(connection) ?: return@createCar
                        currentScope.launch {
                            val result =
                                try {
                                    withContext(Dispatchers.IO) {
                                        copyRestrictions(restrictions.currentCarUxRestrictions)
                                    }
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    null
                                } catch (_: LinkageError) {
                                    null
                                }
                            if (epoch == lifetime) currentGate.resultRestrictions(query, result)
                        }
                    } catch (_: Exception) {
                        clearManager()
                        currentGate.eventRestrictions(connection, null)
                    } catch (_: LinkageError) {
                        clearManager()
                        currentGate.eventRestrictions(connection, null)
                    }
                }
        } catch (_: Exception) {
            currentGate.disconnect()
        } catch (_: LinkageError) {
            currentGate.disconnect()
        }
    }

    /** Copy the complete value object; a missing getter must not publish a partial observation. */
    private fun copyRestrictions(value: CarUxRestrictions?): VehicleUxRestrictions? =
        try {
            value?.let {
                VehicleUxRestrictions(
                    requiresDistractionOptimization = it.isRequiresDistractionOptimization,
                    activeRestrictions = it.activeRestrictions,
                    maxStringLength = it.maxRestrictedStringLength,
                    maxCumulativeContentItems = it.maxCumulativeContentItems,
                    maxContentDepth = it.maxContentDepth,
                )
            }
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    private fun clearManager() {
        val previous = manager
        manager = null
        try {
            previous?.unregisterListener()
        } catch (_: Exception) {} catch (_: LinkageError) {}
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        lifetime++ // Fence callbacks before unregister/disconnect can invoke them.
        gate?.disconnect()
        gate = null
        scope?.cancel()
        scope = null
        clearManager()
        val previous = car
        car = null
        try {
            previous?.disconnect()
        } catch (_: Exception) {} catch (_: LinkageError) {}
    }
}
