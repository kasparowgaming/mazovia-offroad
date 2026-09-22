package pl.mazovia.offroad.domain.gpx

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.domain.model.*
import java.io.ByteArrayOutputStream

class ExactGpxTest {
    private fun fixture(): GpxData = (GpxParser().parse(
        requireNotNull(javaClass.getResourceAsStream("/exact_gpx_fixture.gpx")), "exact_gpx_fixture.gpx"
    ) as GpxParser.ParseResult.Success).data

    @Test fun parserRouteAndExportKeepExactStructure() {
        val gpx = fixture()
        val expected = listOf(
            GeoPoint(52.229700, 21.012200, 100.5), GeoPoint(52.230123, 21.012987),
            GeoPoint(52.231234, 21.013456), GeoPoint(52.250001, 21.030001),
            GeoPoint(52.250999, 21.031999)
        )
        assertEquals(expected, gpx.tracks.single().segments.flatMap { it.points.map { p -> p.point } })
        assertEquals(listOf(3, 2), gpx.tracks.single().segments.map { it.points.size })
        assertEquals("Punkt widokowy", gpx.waypoints.single().name)
        val route = GpxRoute.create(gpx)
        assertEquals(RouteSource.IMPORTED_GPX, route.source)
        assertEquals(gpx, route.originalGpx)
        assertEquals(listOf(3, 2), route.segments.map { it.points.size })
        assertEquals(gpx.totalDistanceMeters, route.totalDistanceMeters, 0.00001)
        val bytes = ByteArrayOutputStream()
        GpxWriter().writeRoute(bytes, route)
        val exported = (GpxParser().parse(bytes.toByteArray().inputStream()) as GpxParser.ParseResult.Success).data
        assertEquals(gpx.tracks, exported.tracks)
        assertEquals(gpx.waypoints, exported.waypoints)
        assertEquals(gpx.name, exported.name)
    }

    @Test fun unsupportedMultipleTracksAreExplicit() {
        val one = fixture()
        val error = runCatching { GpxRoute.create(one.copy(tracks = one.tracks + one.tracks)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
