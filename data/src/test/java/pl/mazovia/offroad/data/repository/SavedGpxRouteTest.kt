package pl.mazovia.offroad.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.data.db.dao.SavedRouteDao
import pl.mazovia.offroad.data.db.entity.SavedRouteEntity
import pl.mazovia.offroad.domain.model.*

class SavedGpxRouteTest {
    @Test fun saveAndReopenPreservesArtifactAndSource() = runBlocking {
        var stored: SavedRouteEntity? = null
        val dao = object : SavedRouteDao {
            override fun getAllRoutes(): Flow<List<SavedRouteEntity>> = flowOf(emptyList())
            override suspend fun getRouteById(id: String) = stored?.takeIf { it.id == id }
            override suspend fun insertRoute(route: SavedRouteEntity) { stored = route }
            override suspend fun deleteRoute(route: SavedRouteEntity) { stored = null }
            override suspend fun getRouteCount() = if (stored == null) 0 else 1
        }
        val gpx = GpxData("Original", "Description", listOf(GpxTrack("Track", listOf(
            GpxSegment(listOf(GpxTrackPoint(GeoPoint(52.0, 21.0)), GpxTrackPoint(GeoPoint(52.001, 21.001)))),
            GpxSegment(listOf(GpxTrackPoint(GeoPoint(52.01, 21.01)), GpxTrackPoint(GeoPoint(52.011, 21.011))))
        ))), listOf(GpxWaypoint(GeoPoint(52.001, 21.001), "Waypoint")), "original.gpx")
        val repository = RouteRepository(dao)
        val saved = repository.saveImportedGpx(gpx)
        val reopened = repository.openRoute(saved.id)
        assertEquals("gpx_import", stored?.source)
        assertEquals(RouteSource.IMPORTED_GPX, reopened.source)
        assertEquals(gpx, reopened.originalGpx)
        assertEquals(listOf(2, 2), reopened.segments.map { it.points.size })
        assertEquals(saved.allPoints, reopened.allPoints)
    }
}
