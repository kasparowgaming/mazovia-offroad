package pl.mazovia.offroad.ui.riding.terrain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerrainSourceBoundaryTest {
    private val main = File("src/main/java/pl/mazovia/offroad/ui/riding/terrain")

    @Test fun fixturesStayInTestSources() {
        val allMain = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected Kotlin production sources under app/src/main", allMain.isNotEmpty())
        assertTrue("Expected terrain presenter in production sources",
            allMain.any { it.name == "TerrainPresenter.kt" })
        val forbidden = listOf("RawElevationProfile.fromHeights", "SyntheticElevationSampler",
            "SyntheticProfile", "GradeProfile.from(", "FilteredElevationProfile.from(",
            "GradeEventDetector", "TerrainPresenter(grade")
        allMain.forEach { source ->
            forbidden.forEach { identifier ->
                assertFalse("${source.path}: $identifier", source.readText().contains(identifier))
            }
        }
    }

    @Test fun rendererAndAdapterDoNotOwnRouteProgress() {
        val forbidden = listOf("TerrainRouteProjection", "RouteIndex", "OffRouteDetector",
            "distanceTo(", "remainingDistanceMeters", "NavigationStatus.ARRIVED", "NavigationStatus.OFF_ROUTE")
        main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TerrainPresenter.kt" }
            .forEach { source ->
                forbidden.forEach { identifier ->
                    assertFalse("${source.path}: $identifier", source.readText().contains(identifier))
                }
            }
    }
}
