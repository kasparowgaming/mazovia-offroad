package pl.mazovia.offroad.domain.model

data class PlaceSearchResult(
    val name: String,
    val localityContext: String?,
    val location: GeoPoint,
    val distanceMeters: Double? = null,
    val isLocality: Boolean = false
)
