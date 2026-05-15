package com.yourcompany.donna

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * LocationModule — Native Android Standort-Modul für React Native.
 * Nutzt direkt Android LocationManager (GPS + Netzwerk + Fused Provider).
 * Kein @react-native-community/geolocation erforderlich.
 */
class LocationModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "LocationModule"

    @ReactMethod
    fun getLastKnownLocation(promise: Promise) {
        try {
            if (ContextCompat.checkSelfPermission(
                    reactContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    reactContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                promise.reject("PERMISSION_DENIED", "Location permission not granted")
                return
            }

            val lm = reactContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                promise.reject("NO_LOCATION_MANAGER", "LocationManager not available")
                return
            }

            val providers = listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            )

            val location: Location? = providers
                .mapNotNull { provider ->
                    runCatching {
                        if (lm.isProviderEnabled(provider))
                            lm.getLastKnownLocation(provider)
                        else null
                    }.getOrNull()
                }
                .maxByOrNull { it.time }

            if (location == null) {
                promise.reject("NO_LOCATION", "No cached location — GPS nicht aktiv oder zu alt")
                return
            }

            val result = Arguments.createMap().apply {
                putDouble("lat", location.latitude)
                putDouble("lon", location.longitude)
                putDouble("accuracy", location.accuracy.toDouble())
                putDouble("timestamp", location.time.toDouble())
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("ERROR", e.localizedMessage ?: "Unknown error")
        }
    }
}
