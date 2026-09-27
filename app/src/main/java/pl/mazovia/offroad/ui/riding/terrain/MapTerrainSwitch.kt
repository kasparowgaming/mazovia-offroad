package pl.mazovia.offroad.ui.riding.terrain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.domain.model.NavigationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.util.Log
import pl.mazovia.offroad.terrain.presentation.CorridorGeometry
import pl.mazovia.offroad.terrain.profile.RouteTerrainProfile
import pl.mazovia.offroad.terrainsource.TerrainProfileSession
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RidingMapTerrainBox(
    mode: RidingViewMode,
    onModeChange: (RidingViewMode) -> Unit,
    terrainAvailable: Boolean,
    map: @Composable () -> Unit,
    terrain: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    unavailableReason: String? = null,
    /** Bottom edge (px, box coordinates) of the overlays drawn over the top of the map. */
    onTopOverlayBottomChanged: (Int) -> Unit = {}
) {
    var switchBottom by remember { mutableIntStateOf(0) }
    var reasonBottom by remember { mutableIntStateOf(0) }
    val showReason = !terrainAvailable && unavailableReason != null
    val topOverlayBottom = if (showReason) max(switchBottom, reasonBottom) else switchBottom
    val currentOnTopOverlayBottomChanged by rememberUpdatedState(onTopOverlayBottomChanged)
    LaunchedEffect(topOverlayBottom) { currentOnTopOverlayBottomChanged(topOverlayBottom) }
    Box(modifier) {
        map()
        if (mode == RidingViewMode.TEREN && terrainAvailable) {
            Box(Modifier.matchParentSize().background(Color(0xFF101B24)).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }) {
                // The MAPA/TEREN switch floats over the top: the instrument starts below it.
                val switchBottomDp = with(LocalDensity.current) { switchBottom.toDp() }
                Box(Modifier.fillMaxSize().padding(top = switchBottomDp)) { terrain() }
            }
        }
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .onGloballyPositioned { switchBottom = it.boundsInParent().bottom.roundToInt() }
        ) {
            RidingViewMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    enabled = option == RidingViewMode.MAPA || terrainAvailable,
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 90.dp)
                        .semantics { role = Role.RadioButton; selected = mode == option },
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Color(0xFF00D5DA),
                        activeContentColor = Color(0xFF071A21),
                        inactiveContainerColor = Color(0xFF162A35),
                        inactiveContentColor = Color.White
                    )
                ) { Text(option.name) }
            }
        }
        if (showReason) {
            Text(unavailableReason, Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
                .onGloballyPositioned { reasonBottom = it.boundsInParent().bottom.roundToInt() }, color = Color.White)
        }
    }
}

@Composable
fun TerrainPane(
    state: NavigationState,
    onFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Presenter reported IDLE (NO_ROUTE): DESIGN §16.1 hides TERRAIN and shows MAP. */
    onNoRoute: () -> Unit = {}
) {
    val presenterResult = remember { runCatching { TerrainPresenter() } }
    val presenter = presenterResult.getOrNull()
    val context = LocalContext.current.applicationContext
    val session = remember { TerrainProfileSession(context) }
    DisposableEffect(session) { onDispose { session.close() } }
    // TA-007: the presenter names the route it projects onto → profile built off the main thread → installed by the
    // collector below. This adapter only passes the request along; route progress stays in the presenter.
    var request by remember { mutableStateOf(presenter?.profileRequest) }
    var profile by remember { mutableStateOf<RouteTerrainProfile?>(null) }
    LaunchedEffect(session) {
        snapshotFlow { request }.distinctUntilChangedBy { it?.routeId }.collectLatest { next ->
            profile = null
            profile = next?.let { session.build(it) }
        }
    }
    val currentState by rememberUpdatedState(state)
    val currentOnNoRoute by rememberUpdatedState(onNoRoute)
    var model by remember { mutableStateOf<TerrainInstrumentModel>(TerrainInstrumentModel.NoRoute) }
    LaunchedEffect(presenter) {
        if (presenter == null) { onFailure("Teren niedostępny"); return@LaunchedEffect }
        var lastNanos = 0L
        fun now(): Long = max(System.nanoTime(), lastNanos).also { lastNanos = it }
        // TA-007B corridor: the presenter keeps the window geometry and rebuilds it only when the rider leaves the
        // built window (≈ every 30 m) or the route/profile changes; a rebuild runs off the main thread (DESIGN §17.1).
        suspend fun corridorFor(frame: TerrainDisplayState): CorridorGeometry? =
            if (presenter.corridorIsCurrent(frame)) presenter.corridor(frame)
            else withContext(Dispatchers.Default) {
                val started = System.nanoTime()
                presenter.corridor(frame).also {
                    Log.d("TerrainCorridor", "window ${it?.size} samples at s=%.0f in %.2f ms".format(
                        frame.distanceAlongM, (System.nanoTime() - started) / 1e6))
                }
            }
        // The emission already fed to the presenter: a profile arriving later only re-derives the frame (no
        // duplicate emission, which would refresh the freshness clock).
        var fed: NavigationState? = null
        try {
            snapshotFlow { currentState to profile }.collectLatest { (incoming, loaded) ->
                // Projection (and the route index build on a new route) runs off the main thread (DESIGN §17.1);
                // calls stay sequential within this coroutine, so the presenter remains single-threaded.
                var current = withContext(Dispatchers.Default) {
                    presenter.setProfile(loaded)
                    if (incoming === fed) presenter.frame(now())
                    else presenter.onNavigationState(incoming, now()).also { fed = incoming }
                }
                request = presenter.profileRequest
                model = terrainInstrumentModel(current, corridorFor(current))
                if (current.mode == TerrainVisualMode.IDLE) { currentOnNoRoute(); return@collectLatest }
                while (true) {
                    if (current.needsAnimation) {
                        withFrameNanos { }
                    } else {
                        val due = current.nextTimedUpdateNanos ?: break
                        delay(max(1L, (due - now()) / 1_000_000L))
                    }
                    current = presenter.frame(now())
                    model = terrainInstrumentModel(current, corridorFor(current))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("TerrainCorridor", "terrain presentation failed", failure)
            onFailure("Teren niedostępny")
        }
    }
    RoadAheadCorridor(model, modifier, onFailure)
}
