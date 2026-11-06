package dev.carplaymate.demo

import android.content.Context
import android.content.pm.PackageManager

/**
 * Start/close and callbacks run on main. Start receives an application-owned context for the
 * attached display; it must not retain an Activity while an audio session continues.
 */
internal interface VehicleAccessProvider {
    val initialState: VehicleAccessState
    val initialSnapshot: VehicleUxSnapshot
        get() = VehicleUxSnapshot(initialState)

    fun start(context: Context, changed: (VehicleAccessState) -> Unit)

    /** Legacy providers have no raw UXR values; never invent limits or flags for them. */
    fun startObserving(context: Context, changed: (VehicleUxSnapshot) -> Unit) {
        start(context) { changed(VehicleUxSnapshot(it)) }
    }

    fun close()
}

internal object VehicleAccessProviders {
    fun create(context: Context): VehicleAccessProvider {
        val automotive =
            try {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            } catch (_: Exception) {
                return FixedAccess(VehicleAccessState.UNKNOWN)
            }
        if (!automotive) {
            return FixedAccess(VehicleAccessState.NON_AUTOMOTIVE_DEMO)
        }
        // android.car is an optional system library. No automotive type appears in this API.
        return try {
            AutomotiveCarAccessProvider()
        } catch (_: LinkageError) {
            FixedAccess(VehicleAccessState.UNKNOWN)
        } catch (_: Exception) {
            FixedAccess(VehicleAccessState.UNKNOWN)
        }
    }

    private class FixedAccess(override val initialState: VehicleAccessState) :
        VehicleAccessProvider {
        override fun start(context: Context, changed: (VehicleAccessState) -> Unit) {
            changed(initialState)
        }

        override fun close() = Unit
    }
}
