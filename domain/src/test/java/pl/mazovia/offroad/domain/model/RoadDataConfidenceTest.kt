package pl.mazovia.offroad.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RoadDataConfidenceTest {
    private val osm = setOf(RoadDataSource.OSM)

    @Test fun surfacePrecedenceAndMethods() {
        val explicit = RoadDataConfidenceResolver.resolve(Surface.GRAVEL, TrackType.GRADE3, HighwayType.TRACK, osm)
        assertEquals(Surface.GRAVEL, explicit.surfaceEvidence.classification)
        assertEquals(ConfidenceLevel.HIGH, explicit.surfaceEvidence.confidence)
        assertEquals(EvidenceMethod.EXPLICIT_TAG, explicit.surfaceEvidence.method)
        assertEquals(osm, explicit.surfaceEvidence.sources)

        val secondary = RoadDataConfidenceResolver.resolve(trackType = TrackType.GRADE3, existenceSources = osm)
        assertEquals(Surface.UNPAVED, secondary.surfaceEvidence.classification)
        assertEquals(ConfidenceLevel.MEDIUM, secondary.surfaceEvidence.confidence)
        assertEquals(EvidenceMethod.SECONDARY_ATTRIBUTE, secondary.surfaceEvidence.method)

        val grade1 = RoadDataConfidenceResolver.resolve(trackType = TrackType.GRADE1, existenceSources = osm)
        assertEquals(Surface.UNKNOWN, grade1.surfaceEvidence.classification)
        assertEquals(ConfidenceLevel.UNKNOWN, grade1.surfaceEvidence.confidence)
        assertEquals(EvidenceMethod.SECONDARY_ATTRIBUTE, grade1.surfaceEvidence.method)

        val broad = RoadDataConfidenceResolver.resolve(highway = HighwayType.TRACK, existenceSources = osm)
        assertEquals(Surface.UNKNOWN, broad.surfaceEvidence.classification)
        assertEquals(ConfidenceLevel.UNKNOWN, broad.surfaceEvidence.confidence)
        assertEquals(EvidenceMethod.DERIVED_FROM_ROAD_CLASS, broad.surfaceEvidence.method)
        assertEquals(SurfaceEvidence.UNKNOWN, RoadDataConfidenceResolver.resolve().surfaceEvidence)
    }

    @Test fun existenceAndIndependentProvenance() {
        val onlyOsm = RoadDataConfidenceResolver.resolve(existenceSources = osm)
        assertEquals(ConfidenceLevel.MEDIUM, onlyOsm.existenceEvidence.confidence)
        assertEquals(EvidenceMethod.SOURCE_REPRESENTATION, onlyOsm.existenceEvidence.method)

        val both = RoadDataConfidenceResolver.resolve(Surface.GRAVEL,
            existenceSources = setOf(RoadDataSource.OSM, RoadDataSource.RIDE_TRACE))
        assertEquals(ConfidenceLevel.HIGH, both.existenceEvidence.confidence)
        assertEquals(EvidenceMethod.MULTI_SOURCE_CORROBORATION, both.existenceEvidence.method)
        assertEquals(setOf(RoadDataSource.OSM, RoadDataSource.RIDE_TRACE), both.existenceEvidence.sources)
        assertEquals(osm, both.surfaceEvidence.sources)
        assertEquals(EvidenceMethod.EXPLICIT_TAG, both.surfaceEvidence.method)

        val trace = RoadDataConfidenceResolver.resolve(existenceSources = setOf(RoadDataSource.RIDE_TRACE))
        assertEquals(ConfidenceLevel.MEDIUM, trace.existenceEvidence.confidence)
        assertEquals(EvidenceMethod.OBSERVED_TRACE, trace.existenceEvidence.method)
        assertEquals(ExistenceEvidence.UNKNOWN, RoadDataConfidenceResolver.resolve().existenceEvidence)
    }

    @Test fun sourceInvariant() {
        assertEquals(osm, RoadDataConfidenceResolver.resolve(existenceSources = osm).existenceEvidence.sources)
        assertEquals(setOf(RoadDataSource.UNKNOWN), ExistenceEvidence.UNKNOWN.sources)
        assertEquals(osm, RoadDataConfidenceResolver.resolve(
            existenceSources = setOf(RoadDataSource.OSM, RoadDataSource.UNKNOWN)).existenceEvidence.sources)
        try {
            ExistenceEvidence(ConfidenceLevel.MEDIUM, setOf(RoadDataSource.OSM, RoadDataSource.UNKNOWN),
                EvidenceMethod.SOURCE_REPRESENTATION)
            fail("mixed source set accepted")
        } catch (_: IllegalArgumentException) { }
    }

    private fun segment(distance: Double, confidence: RoadDataConfidence) =
        RouteSegment(listOf(GeoPoint(52.0, 21.0), GeoPoint(52.001, 21.0)),
            distance, Surface.UNKNOWN, HighwayType.UNKNOWN, roadDataConfidence = confidence)

    @Test fun distanceWeightedExclusiveCoverageAndZero() {
        val high = RoadDataConfidenceResolver.resolve(Surface.GRAVEL,
            existenceSources = setOf(RoadDataSource.OSM, RoadDataSource.RIDE_TRACE))
        val unknown = RoadDataConfidence.UNKNOWN
        val inferred = RoadDataConfidenceResolver.resolve(trackType = TrackType.GRADE3, existenceSources = osm)
        val summary = RouteDataConfidenceSummary.fromSegments(
            listOf(segment(800.0, high), segment(100.0, inferred), segment(100.0, unknown)))
        assertEquals(1000.0, summary.totalDistanceMeters, 0.0)
        assertEquals(80.0, summary.percentage(summary.highExistenceDistanceMeters), 0.0)
        assertEquals(10.0, summary.percentage(summary.unknownExistenceDistanceMeters), 0.0)
        assertEquals(summary.totalDistanceMeters,
            summary.explicitSurfaceDistanceMeters + summary.inferredSurfaceDistanceMeters +
                summary.unknownSurfaceDistanceMeters, 1e-9)
        assertEquals(summary.totalDistanceMeters,
            summary.highExistenceDistanceMeters + summary.mediumExistenceDistanceMeters +
                summary.lowExistenceDistanceMeters + summary.unknownExistenceDistanceMeters, 1e-9)
        val zero = RouteDataConfidenceSummary.fromSegments(listOf(segment(0.0, unknown)))
        assertEquals(0.0, zero.percentage(zero.unknownSurfaceDistanceMeters), 0.0)
    }

    @Test fun preTask008RoutePayloadLoadsWithUnknownEvidence() {
        // Static pre-TASK-008 schema: complete required route and metrics,
        // including the old segment dataConfidence, with no roadDataConfidence.
        val legacy = """
            {
              "id":"legacy-route", "origin":{"latitude":52.0,"longitude":21.0},
              "destination":{"latitude":52.001,"longitude":21.0},
              "segments":[{"points":[{"latitude":52.0,"longitude":21.0},{"latitude":52.001,"longitude":21.0}],
                "distanceMeters":111.0,"surface":"GRAVEL","highway":"TRACK","dataConfidence":"CONFIRMED"}],
              "metrics":{"totalDistanceMeters":111.0,"estimatedTimeSeconds":12,
                "asphaltDistanceMeters":0.0,"offRoadDistanceMeters":111.0,
                "longestAsphaltConnectorMeters":0.0,"surfaceDistribution":{"GRAVEL":1.0},
                "dataConfidenceScore":1.0},
              "profile":"TERENOWY"
            }
        """.trimIndent()
        val route = Json { ignoreUnknownKeys = true }.decodeFromString(Route.serializer(), legacy)
        assertEquals("legacy-route", route.id)
        assertEquals(RoadDataConfidence.UNKNOWN, route.segments.single().roadDataConfidence)
        assertEquals(111.0, route.roadDataConfidenceSummary.unknownSurfaceDistanceMeters, 0.0)
    }
}
