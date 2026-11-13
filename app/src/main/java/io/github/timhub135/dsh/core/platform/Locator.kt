package io.github.timhub135.dsh.core.platform

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot current location: fused location when Google Play services is present, otherwise the
 * platform LocationManager (many CN ROMs). Cancellable; null when no fix arrives in time.
 * Callers check the location permission first.
 */
@SuppressLint("MissingPermission")
object Locator {
    private const val FIX_TIMEOUT_MS = 20_000L
    private const val FRESH_MS = 2 * 60_000L

    private fun hasPlayServices(context: Context) = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    suspend fun current(context: Context, precise: Boolean): Location? = withTimeoutOrNull(FIX_TIMEOUT_MS) {
        if (hasPlayServices(context)) runCatching { fused(context, precise) }.getOrNull()?.let { return@withTimeoutOrNull it }
        platform(context, precise)
    } ?: lastKnown(context)

    private suspend fun fused(context: Context, precise: Boolean): Location? = suspendCancellableCoroutine { cont ->
        val cts = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        cont.invokeOnCancellation { cts.cancel() }
    }

    private suspend fun platform(context: Context, precise: Boolean): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        val enabled = lm.getProviders(true)
        val provider = when {
            precise && LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            enabled.isNotEmpty() -> enabled.first()
            else -> return null
        }
        return suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { if (cont.isActive) cont.resume(it) }
                cont.invokeOnCancellation { signal.cancel() }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { if (cont.isActive) cont.resume(l) }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
    }

    private fun lastKnown(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time < FRESH_MS * 15 } // shown with its age; drop anything older than 30 min
    }

    /** Street address from the system geocoder, or null (absent on many devices / no network). */
    suspend fun address(context: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(context)
        return withTimeoutOrNull(5_000) {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine<String?> { cont ->
                g.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(list: MutableList<android.location.Address>) { if (cont.isActive) cont.resume(list.firstOrNull()?.let(::line)) }
                    override fun onError(message: String?) { if (cont.isActive) cont.resume(null) }
                })
            } else runCatching {
                runInterruptible(Dispatchers.IO) { @Suppress("DEPRECATION") g.getFromLocation(lat, lng, 1)?.firstOrNull()?.let(::line) }
            }.getOrNull()
        }
    }

    private fun line(a: android.location.Address): String =
        (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ").ifEmpty {
            listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.adminArea, a.countryName).joinToString(", ")
        }
}
