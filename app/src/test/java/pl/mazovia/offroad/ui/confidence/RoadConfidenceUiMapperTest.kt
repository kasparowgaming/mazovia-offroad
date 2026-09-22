package pl.mazovia.offroad.ui.confidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import pl.mazovia.offroad.domain.model.*

class RoadConfidenceUiMapperTest {
    private fun confidence(surface: Surface, level: ConfidenceLevel, method: EvidenceMethod) =
        RoadDataConfidence(
            ExistenceEvidence(ConfidenceLevel.MEDIUM, setOf(RoadDataSource.ROUTING_GRAPH),
                EvidenceMethod.SOURCE_REPRESENTATION),
            SurfaceEvidence(surface, level, setOf(RoadDataSource.ROUTING_GRAPH), method)
        )

    private fun route(vararg items: Pair<Double, RoadDataConfidence>): Route {
        val point = GeoPoint(52.0, 21.0)
        return Route("test", point, point, items.map { (distance, evidence) ->
            RouteSegment(listOf(point, point), distance, Surface.UNKNOWN, HighwayType.UNKNOWN,
                roadDataConfidence = evidence)
        }, RouteMetrics.EMPTY, RoutingProfile.TERENOWY)
    }

    @Test fun categoriesDependOnClassificationAndConfidence() {
        assertEquals(SurfaceDataCategory.DEFINED, RoadConfidenceUiMapper.category(
            confidence(Surface.GRAVEL, ConfidenceLevel.HIGH, EvidenceMethod.MULTI_SOURCE_CORROBORATION)))
        assertEquals(SurfaceDataCategory.ESTIMATED, RoadConfidenceUiMapper.category(
            confidence(Surface.UNPAVED, ConfidenceLevel.MEDIUM, EvidenceMethod.SECONDARY_ATTRIBUTE)))
        assertEquals(SurfaceDataCategory.ESTIMATED, RoadConfidenceUiMapper.category(
            confidence(Surface.DIRT, ConfidenceLevel.LOW, EvidenceMethod.DERIVED_FROM_ROAD_CLASS)))
        assertEquals(SurfaceDataCategory.UNKNOWN, RoadConfidenceUiMapper.category(
            confidence(Surface.UNKNOWN, ConfidenceLevel.UNKNOWN, EvidenceMethod.SECONDARY_ATTRIBUTE)))
        assertEquals(SurfaceDataCategory.UNKNOWN, RoadConfidenceUiMapper.category(
            confidence(Surface.UNKNOWN, ConfidenceLevel.HIGH, EvidenceMethod.ENCODED_SURFACE_VALUE)))
    }

    @Test fun weightedCoverageAndAllKnown() {
        val high = confidence(Surface.GRAVEL, ConfidenceLevel.HIGH, EvidenceMethod.ENCODED_SURFACE_VALUE)
        val medium = confidence(Surface.UNPAVED, ConfidenceLevel.MEDIUM, EvidenceMethod.SECONDARY_ATTRIBUTE)
        val mixed = RoadConfidenceUiMapper.map(route(
            800.0 to high, 100.0 to medium, 100.0 to RoadDataConfidence.UNKNOWN))
        assertEquals(SurfaceDataAvailability.AVAILABLE, mixed.availability)
        assertEquals(listOf(80, 10, 10), mixed.rows.map { it.percent })
        assertEquals(1000.0, mixed.rows.sumOf { it.distanceMeters }, 0.0)
        assertEquals(100, mixed.rows.sumOf { it.percent })
        assertEquals(listOf(100, 0, 0), RoadConfidenceUiMapper.map(route(100.0 to high))
            .rows.map { it.percent })
    }

    @Test fun allUnknownAndZeroDistanceAreUnavailable() {
        // This is the UI state of an opened legacy route whose new field defaulted to UNKNOWN.
        val legacy = RoadConfidenceUiMapper.map(route(500.0 to RoadDataConfidence.UNKNOWN))
        assertEquals(SurfaceDataAvailability.ALL_UNKNOWN, legacy.availability)
        assertEquals(listOf(0, 0, 100), legacy.rows.map { it.percent })
        val zero = RoadConfidenceUiMapper.map(route(0.0 to RoadDataConfidence.UNKNOWN))
        assertEquals(SurfaceDataAvailability.NO_DISTANCE, zero.availability)
        assertEquals(emptyList<SurfaceCoverageRow>(), zero.rows)
    }

    @Test fun largestRemainderAlwaysDisplaysOneHundred() {
        val high = confidence(Surface.GRAVEL, ConfidenceLevel.HIGH, EvidenceMethod.EXPLICIT_TAG)
        val medium = confidence(Surface.UNPAVED, ConfidenceLevel.MEDIUM, EvidenceMethod.SECONDARY_ATTRIBUTE)
        val state = RoadConfidenceUiMapper.map(route(
            33.5 to high, 33.5 to medium, 33.0 to RoadDataConfidence.UNKNOWN))
        assertEquals(listOf(34, 33, 33), state.rows.map { it.percent })
        assertEquals(100, state.rows.sumOf { it.percent })
    }

    @Test fun terminologyDoesNotInventProvenance() {
        val state = RoadConfidenceUiMapper.map(route(100.0 to confidence(
            Surface.GRAVEL, ConfidenceLevel.HIGH, EvidenceMethod.ENCODED_SURFACE_VALUE)))
        val copy = state.rows.joinToString { it.category.label } + (state.roadDataNote ?: "")
        assertFalse(copy.contains("OSM"))
        assertFalse(copy.contains("BDOT"))
        assertFalse(copy.contains("potwierdzona", ignoreCase = true))
    }
}
