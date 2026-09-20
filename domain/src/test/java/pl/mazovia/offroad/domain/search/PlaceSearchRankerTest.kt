package pl.mazovia.offroad.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.PlaceSearchResult

class PlaceSearchRankerTest {

    private val anchor = GeoPoint(52.1567802, 22.3448868) // Siedlce

    @Test
    fun testPrefixOutranksContains() {
        val candidates = listOf(
            PlaceSearchResult("Szczecin", null, GeoPoint(53.4, 14.5), isLocality = true), // Contains 'cze'
            PlaceSearchResult("Częstochowa", null, GeoPoint(50.8, 19.1), isLocality = true) // Prefix 'cze'
        )
        val ranked = PlaceSearchRanker.rank(candidates, "cze", anchor)
        
        assertEquals(2, ranked.size)
        assertEquals("Częstochowa", ranked[0].name)
    }

    @Test
    fun testNearbyPrefixOutranksDistantPrefix() {
        val candidates = listOf(
            PlaceSearchResult("Zabłocie", null, GeoPoint(50.0, 19.9), isLocality = true), // Far
            PlaceSearchResult("Zabokliki", null, GeoPoint(52.17, 22.37), isLocality = true) // Near
        )
        val ranked = PlaceSearchRanker.rank(candidates, "zab", anchor)
        
        assertEquals(2, ranked.size)
        assertEquals("Zabokliki", ranked[0].name)
    }

    @Test
    fun testDuplicatesRemoved() {
        val candidates = listOf(
            PlaceSearchResult("Zabokliki", "mazowieckie", GeoPoint(52.17, 22.37), isLocality = true),
            PlaceSearchResult("Zabokliki", "Siedlce", GeoPoint(52.171, 22.371), isLocality = true) // Close enough to be duplicate
        )
        val ranked = PlaceSearchRanker.rank(candidates, "zab", anchor)
        
        assertEquals(1, ranked.size)
    }
    
    @Test
    fun testNoSearchForShortQuery() {
        val candidates = listOf(
            PlaceSearchResult("Zabokliki", null, GeoPoint(52.17, 22.37), isLocality = true)
        )
        // Note: The viewModel guards against <3 chars, but ranker should also return empty for blank
        val ranked = PlaceSearchRanker.rank(candidates, "  ", anchor)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun testLocalityBoost() {
        val candidates = listOf(
            PlaceSearchResult("Zabka (sklep)", null, GeoPoint(52.16, 22.36), isLocality = false), // Near but POI
            PlaceSearchResult("Zabokliki", null, GeoPoint(52.17, 22.37), isLocality = true) // Near and Locality
        )
        val ranked = PlaceSearchRanker.rank(candidates, "zab", anchor)
        assertEquals("Zabokliki", ranked[0].name)
    }
}
