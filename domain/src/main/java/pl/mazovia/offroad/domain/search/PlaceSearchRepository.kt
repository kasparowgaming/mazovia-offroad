package pl.mazovia.offroad.domain.search

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.PlaceSearchResult

interface PlaceSearchRepository {
    /**
     * Search for places near the anchor.
     * The repository should ideally use bounding box/radius if supported,
     * and fallback to a global search if no local results are found.
     */
    suspend fun searchPlaces(query: String, anchor: GeoPoint): List<PlaceSearchResult>
}
