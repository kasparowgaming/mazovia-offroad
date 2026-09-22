package pl.mazovia.offroad.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.mazovia.offroad.data.db.dao.SavedRouteDao
import pl.mazovia.offroad.data.db.entity.SavedRouteEntity
import pl.mazovia.offroad.domain.model.RoadDataConfidence

class LegacySavedRouteTest {
    @Test fun openRouteDecodesRemovedFieldAndDefaultsNewEvidence() = runBlocking {
        // Representative complete pre-TASK-008 payload: old field is present,
        // while roadDataConfidence and hasSurfaceOrRoadClassDetail are absent.
        val legacyJson = """
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
        val entity = SavedRouteEntity("legacy-route", "Legacy", 1L, 52.0, 21.0,
            52.001, 21.0, "TERENOWY", 111.0, 100.0, legacyJson)
        val dao = object : SavedRouteDao {
            override fun getAllRoutes(): Flow<List<SavedRouteEntity>> = flowOf(listOf(entity))
            override suspend fun getRouteById(id: String): SavedRouteEntity? =
                entity.takeIf { it.id == id }
            override suspend fun insertRoute(route: SavedRouteEntity) = Unit
            override suspend fun deleteRoute(route: SavedRouteEntity) = Unit
            override suspend fun getRouteCount(): Int = 1
        }
        val route = RouteRepository(dao).openRoute("legacy-route")
        assertEquals(RoadDataConfidence.UNKNOWN, route.segments.single().roadDataConfidence)
        assertEquals(111.0, route.roadDataConfidenceSummary.unknownSurfaceDistanceMeters, 0.0)
    }
}
