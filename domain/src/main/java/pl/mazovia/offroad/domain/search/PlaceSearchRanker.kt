package pl.mazovia.offroad.domain.search

import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.PlaceSearchResult
import pl.mazovia.offroad.domain.model.StringNormalization

object PlaceSearchRanker {
    
    fun rank(
        candidates: List<PlaceSearchResult>,
        query: String,
        anchor: GeoPoint
    ): List<PlaceSearchResult> {
        val normalizedQuery = StringNormalization.normalizeForSearch(query)
        if (normalizedQuery.isBlank()) return emptyList()

        // 1. Remove duplicates (same name and very close distance, e.g., within 500m)
        val deduplicated = mutableListOf<PlaceSearchResult>()
        for (candidate in candidates) {
            val isDuplicate = deduplicated.any { 
                it.name == candidate.name && it.location.distanceTo(candidate.location) < 500
            }
            if (!isDuplicate) {
                deduplicated.add(candidate)
            }
        }

        // 2. Score and classify into Tiers
        val ranked = deduplicated.mapNotNull { candidate ->
            val normalizedName = StringNormalization.normalizeForSearch(candidate.name)
            val words = normalizedName.split(Regex("\\s+"))
            
            val tier = when {
                normalizedName.startsWith(normalizedQuery) -> 1
                words.any { it.startsWith(normalizedQuery) } -> 2
                normalizedName.contains(normalizedQuery) -> 3
                // Fuzzy matching (optional Tier 4, skipping for simplicity unless needed)
                else -> null
            }

            if (tier != null) {
                val dist = candidate.location.distanceTo(anchor)
                val updatedCandidate = candidate.copy(distanceMeters = dist)
                Triple(updatedCandidate, tier, dist)
            } else {
                null
            }
        }

        // 3. Sort by Tier (asc), then by locality (true first), then by distance (asc)
        return ranked.sortedWith(Comparator { a, b ->
            if (a.second != b.second) {
                a.second.compareTo(b.second)
            } else {
                val aLocality = a.first.isLocality
                val bLocality = b.first.isLocality
                if (aLocality != bLocality) {
                    if (aLocality) -1 else 1
                } else {
                    a.third.compareTo(b.third)
                }
            }
        }).map { it.first }.take(7)
    }
}
