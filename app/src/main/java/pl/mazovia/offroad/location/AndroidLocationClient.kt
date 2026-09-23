package pl.mazovia.offroad.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import pl.mazovia.offroad.domain.location.LocationClient
import pl.mazovia.offroad.domain.location.LocationUpdate
import pl.mazovia.offroad.domain.model.GeoPoint

class AndroidLocationClient(
    private val context: Context
) : LocationClient {

    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun getLocationUpdates(intervalMs: Long): Flow<LocationUpdate> = callbackFlow {
        if (!hasLocationPermission(context)) {
            throw LocationClient.LocationException("Brak uprawnień do lokalizacji")
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        
        if (!isGpsEnabled && !isNetworkEnabled) {
            throw LocationClient.LocationException("GPS jest wyłączony")
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                result.locations.lastOrNull()?.let { location ->
                    val update = LocationUpdate(
                        point = GeoPoint(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            elevation = if (location.hasAltitude()) location.altitude else null
                        ),
                        speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                        bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                        elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                        speedAccuracyMps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null
                    )
                    trySend(update)
                }
            }
        }

        client.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )

        awaitClose {
            client.removeLocationUpdates(locationCallback)
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
