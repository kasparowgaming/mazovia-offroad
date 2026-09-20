package pl.mazovia.offroad.routing.engine

import com.graphhopper.ResponsePath
import com.graphhopper.util.details.PathDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import pl.mazovia.offroad.domain.model.DataConfidence
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.HighwayType
import pl.mazovia.offroad.domain.model.Surface
import pl.mazovia.offroad.domain.model.TrackType

class UnionBoundarySegmentationTest {

    private val engine = GraphHopperRoutingEngine()

    private fun createResponsePath(details: Map<String, List<PathDetail>>): ResponsePath {
        val path = ResponsePath()
        path.addPathDetails(details)
        return path
    }

    private fun createPoints(count: Int): List<GeoPoint> {
        return (0 until count).map { GeoPoint(52.0 + it * 0.001, 21.0) }
    }

    @Test
    fun testMisalignedBoundaries() {
        val details = mapOf(
            "surface" to listOf(
                PathDetail("asphalt").apply { first = 0; last = 2 },
                PathDetail("gravel").apply { first = 2; last = 5 }
            ),
            "road_class" to listOf(
                PathDetail("primary").apply { first = 0; last = 3 },
                PathDetail("track").apply { first = 3; last = 5 }
            ),
            "track_type" to listOf(
                PathDetail("grade1").apply { first = 3; last = 4 },
                PathDetail("grade2").apply { first = 4; last = 5 }
            )
        )

        val segments = engine.extractSegments(createResponsePath(details), createPoints(6))

        assertEquals(4, segments.size)

        // Segment 1: 0..2 (surface=asphalt, road_class=primary)
        assertEquals(3, segments[0].points.size)
        assertEquals(Surface.ASPHALT, segments[0].surface)
        assertEquals(HighwayType.PRIMARY, segments[0].highway)
        assertEquals(TrackType.UNKNOWN, segments[0].trackType)

        // Segment 2: 2..3 (surface=gravel, road_class=primary)
        assertEquals(2, segments[1].points.size)
        assertEquals(Surface.GRAVEL, segments[1].surface)
        assertEquals(HighwayType.PRIMARY, segments[1].highway)
        assertEquals(TrackType.UNKNOWN, segments[1].trackType)

        // Segment 3: 3..4 (surface=gravel, road_class=track, track_type=grade1)
        assertEquals(2, segments[2].points.size)
        assertEquals(Surface.GRAVEL, segments[2].surface)
        assertEquals(HighwayType.TRACK, segments[2].highway)
        assertEquals(TrackType.GRADE1, segments[2].trackType)

        // Segment 4: 4..5 (surface=gravel, road_class=track, track_type=grade2)
        assertEquals(2, segments[3].points.size)
        assertEquals(Surface.GRAVEL, segments[3].surface)
        assertEquals(HighwayType.TRACK, segments[3].highway)
        assertEquals(TrackType.GRADE2, segments[3].trackType)
    }

    @Test
    fun testMissingMustNotMeanConfirmedSurface() {
        val details = mapOf(
            "surface" to listOf(
                PathDetail("missing").apply { first = 0; last = 2 },
                PathDetail("dirt").apply { first = 2; last = 3 }
            ),
            "road_class" to listOf(
                PathDetail("track").apply { first = 0; last = 3 }
            )
        )

        val segments = engine.extractSegments(createResponsePath(details), createPoints(4))

        assertEquals(2, segments.size)

        // Segment 1: 0..2
        assertEquals(Surface.UNKNOWN, segments[0].surface)
        assertEquals(HighwayType.TRACK, segments[0].highway)
        assertEquals(DataConfidence.CONFIRMED, segments[0].dataConfidence) // confirmed because road_class is known
        assertTrue(segments[0].isOffRoad) // CASE A: missing surface + track/path = offroad

        // Segment 2: 2..3
        assertEquals(Surface.DIRT, segments[1].surface)
        assertEquals(HighwayType.TRACK, segments[1].highway)
        assertEquals(DataConfidence.CONFIRMED, segments[1].dataConfidence)
    }

    @Test
    fun `CASE B - MISSING surface and PRIMARY road is not terrain`() {
        val details = mapOf(
            "surface" to listOf(
                PathDetail("missing").apply { first = 0; last = 2 }
            ),
            "road_class" to listOf(
                PathDetail("primary").apply { first = 0; last = 2 }
            )
        )

        val segments = engine.extractSegments(createResponsePath(details), createPoints(3))

        assertEquals(1, segments.size)
        assertEquals(Surface.UNKNOWN, segments[0].surface)
        assertFalse(segments[0].isOffRoad)
        assertTrue(segments[0].isAsphalt)
    }

    @Test
    fun `CASE B2 - MISSING surface and PATH road is terrain`() {
        val details = mapOf(
            "surface" to listOf(
                PathDetail("missing").apply { first = 0; last = 2 }
            ),
            "road_class" to listOf(
                PathDetail("path").apply { first = 0; last = 2 }
            )
        )

        val segments = engine.extractSegments(createResponsePath(details), createPoints(3))

        assertEquals(1, segments.size)
        assertEquals(Surface.UNKNOWN, segments[0].surface)
        assertEquals(HighwayType.PATH, segments[0].highway)
        assertTrue(segments[0].isOffRoad)
        assertFalse(segments[0].isAsphalt)
    }

    @Test
    fun `CASE C - metadata family contains a gap in the middle yields UNKNOWN fallback`() {
        val details = mapOf(
            "surface" to listOf(
                PathDetail("asphalt").apply { first = 0; last = 2 },
                // GAP between 2..4
                PathDetail("dirt").apply { first = 4; last = 6 }
            )
        )

        val segments = engine.extractSegments(createResponsePath(details), createPoints(7))

        assertEquals(3, segments.size)
        assertEquals(Surface.ASPHALT, segments[0].surface)

        // Gap segment
        assertEquals(Surface.UNKNOWN, segments[1].surface)
        assertEquals(DataConfidence.UNKNOWN, segments[1].dataConfidence)

        assertEquals(Surface.DIRT, segments[2].surface)
    }

    @Test
    fun `CASE D, E, F, G - final edge retained, no edge counted twice, sum approx equals source, 3-way misaligned`() {
        val points = createPoints(10)
        var totalSourceDistance = 0.0
        for (i in 0 until points.size - 1) {
            totalSourceDistance += points[i].distanceTo(points[i+1])
        }

        val details = mapOf(
            "surface" to listOf(
                PathDetail("asphalt").apply { first = 0; last = 3 },
                PathDetail("gravel").apply { first = 3; last = 9 } // Ends at 9 (final route point)
            ),
            "road_class" to listOf(
                PathDetail("primary").apply { first = 0; last = 4 },
                PathDetail("track").apply { first = 4; last = 9 }
            ),
            "track_type" to listOf(
                PathDetail("grade1").apply { first = 0; last = 5 },
                PathDetail("grade2").apply { first = 5; last = 9 }
            )
        )

        // 3-way misaligned:
        // surface changes at 3
        // road_class changes at 4
        // track_type changes at 5
        // expected boundaries: 0, 3, 4, 5, 9 => 4 segments

        val path = createResponsePath(details)
        path.distance = totalSourceDistance

        val segments = engine.extractSegments(path, points)

        assertEquals(4, segments.size)

        var totalSegmentDistance = 0.0
        segments.forEach { totalSegmentDistance += it.distanceMeters }

        // F: sum of segment distances approx equals total source
        assertEquals(totalSourceDistance, totalSegmentDistance, 0.1)

        // D: final edge retained
        val lastSegment = segments.last()
        assertEquals(GeoPoint(52.0 + 9 * 0.001, 21.0), lastSegment.points.last())
        assertEquals(Surface.GRAVEL, lastSegment.surface)
    }
}
