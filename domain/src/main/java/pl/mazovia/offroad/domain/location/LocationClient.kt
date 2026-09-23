package pl.mazovia.offroad.domain.location

import kotlinx.coroutines.flow.Flow
import pl.mazovia.offroad.domain.model.GeoPoint

data class LocationUpdate(
    val point: GeoPoint,
    val speedMps: Double?,
    val bearing: Double?,
    val accuracyMeters: Double? = null,
    val elapsedRealtimeNanos: Long? = null,
    val speedAccuracyMps: Double? = null
)

interface LocationClient {
    fun getLocationUpdates(intervalMs: Long): Flow<LocationUpdate>
    
    class LocationException(message: String) : Exception(message)
}
