package pl.mazovia.offroad.ui.riding.terrain

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.mazovia.offroad.debug.NavigationStateReplayer as Replay
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.RawElevationProfile

@RunWith(AndroidJUnit4::class)
class TerrainRidingUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun idleNavigationReturnsToMap() {
        rule.setContent {
            var mode by rememberSaveable { mutableStateOf(RidingViewMode.TEREN) }
            RidingMapTerrainBox(mode, { mode = it }, true, map = { Text("map", Modifier.testTag("fake_map")) },
                terrain = { TerrainPane(pl.mazovia.offroad.domain.model.NavigationState(
                    pl.mazovia.offroad.domain.model.NavigationStatus.IDLE), {}, Modifier.fillMaxSize(),
                    onNoRoute = { mode = RidingViewMode.MAPA }) },
                modifier = Modifier.fillMaxSize())
        }
        rule.waitUntil(5000) {
            rule.onAllNodes(hasText("MAPA") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("TEREN").assertIsNotSelected()
        rule.onNodeWithTag("fake_map").assertExists()
        rule.onNodeWithTag("terrain_instrument").assertDoesNotExist()
    }

    @Test fun switchSelectionBoundsAndMapPreservation() {
        var compositions = 0
        var disposals = 0
        var originalCamera: Any? = null
        var returnedCamera: Any? = null
        var recompositionTrigger by mutableIntStateOf(0)
        rule.setContent {
            var mode by rememberSaveable { mutableStateOf(RidingViewMode.MAPA) }
            RidingMapTerrainBox(mode, { mode = it }, true, map = {
                val camera = remember { mutableStateOf(7) }
                if (originalCamera == null) originalCamera = camera
                returnedCamera = camera
                DisposableEffect(Unit) { compositions++; onDispose { disposals++ } }
                Text("MAP CONTENT ${camera.value} $recompositionTrigger", Modifier.testTag("fake_map"))
            }, terrain = { Text("TERRAIN CONTENT") }, modifier = Modifier.fillMaxSize())
        }
        rule.onNodeWithText("MAPA").assertIsSelected().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithText("TEREN").assertIsNotSelected().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onAllNodes(isSelected()).assertCountEquals(1)
        rule.onNodeWithText("TEREN").performClick()
        rule.onNodeWithText("TEREN").assertIsSelected()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
        rule.onNodeWithText("TERRAIN CONTENT").assertExists()
        rule.runOnIdle { recompositionTrigger++ }
        rule.onNodeWithText("TEREN").assertIsSelected()
        rule.onNodeWithText("MAPA").performClick()
        rule.onNodeWithText("MAPA").assertIsSelected()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
        rule.onNodeWithTag("fake_map").assertExists()
        rule.runOnIdle {
            assertEquals(1, compositions)
            assertEquals(0, disposals)
            assertSame(originalCamera, returnedCamera)
            assertEquals(7, (returnedCamera as MutableState<Int>).value)
        }
    }

    @Test fun unavailableTerrainLeavesMapSelectedAndVisible() {
        rule.setContent {
            var mode by rememberSaveable { mutableStateOf(RidingViewMode.MAPA) }
            RidingMapTerrainBox(mode, { mode = it }, false,
                map = { Text("MAP CONTENT", Modifier.testTag("fake_map")) },
                terrain = { Text("TERRAIN CONTENT") }, modifier = Modifier.fillMaxSize())
        }
        rule.onNodeWithText("TEREN").assertIsNotEnabled()
        rule.onNodeWithText("MAPA").assertIsSelected()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
        rule.onNodeWithTag("fake_map").assertExists()
        rule.onNodeWithText("TERRAIN CONTENT").assertDoesNotExist()
    }

    @Test fun savedSelectionAndNoDataPane() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            var mode by rememberSaveable { mutableStateOf(RidingViewMode.MAPA) }
            RidingMapTerrainBox(mode, { mode = it }, true, map = { Text("map") },
                terrain = { TerrainPane(Replay.state(Replay.route(), 100.0, 0.0), {}, Modifier.fillMaxSize()) },
                modifier = Modifier.fillMaxSize())
        }
        rule.onNodeWithText("TEREN").performClick()
        rule.onNodeWithText("TEREN").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("TEREN").assertIsSelected()
        rule.onNodeWithTag("terrain_instrument").assertExists()
        rule.waitUntil(5000) {
            rule.onAllNodesWithContentDescription("Teren: brak danych").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun validFixtureHasDistanceAndSlope() {
        val grade = GradeProfile.from(FilteredElevationProfile.from(
            RawElevationProfile.fromHeights("fixture", (0..200).map { 100.0 + it * .2 })), emptyList())
        val presenter = TerrainPresenter(grade = grade)
        val model = terrainInstrumentModel(presenter.onNavigationState(
            Replay.state(Replay.route(), 100.0, 0.0), 1L))
        rule.setContent { RoadAheadInstrument(model, Modifier.fillMaxSize()) }
        rule.onNodeWithContentDescription("Teren: dane").assertExists()
        rule.onNodeWithTag("terrain_distance").assertExists()
        rule.onNodeWithTag("terrain_slope").assertExists()
    }
}
