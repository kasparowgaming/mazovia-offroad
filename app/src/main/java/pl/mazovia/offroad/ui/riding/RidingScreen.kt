package pl.mazovia.offroad.ui.riding

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import pl.mazovia.offroad.designsystem.components.*
import pl.mazovia.offroad.designsystem.theme.MazoviaColors
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.state.AppModeManager
import pl.mazovia.offroad.state.MapEngine
import pl.mazovia.offroad.ui.map.components.MapLibreViewContainer
import pl.mazovia.offroad.ui.map.components.MapViewContainer

/**
 * RIDING MODE - radically simpler UI.
 * No bottom navigation, no planning controls, no layer clutter.
 *
 * Portrait: maneuver top, map center, data bottom.
 * Landscape: guidance left, map right.
 */
@Composable
fun RidingScreen(
    navigationManager: NavigationManager,
    appModeManager: AppModeManager,
    sessionRepository: pl.mazovia.offroad.data.repository.SessionRepository
) {
    val navState by navigationManager.navigationState.collectAsState()
    val recordingSession by produceState<pl.mazovia.offroad.data.db.entity.RecordingSessionEntity?>(initialValue = null) {
        while (true) {
            value = sessionRepository.getActiveRecordingSession()
            kotlinx.coroutines.delay(1000L) // poll slightly faster for responsive shutdown
        }
    }
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = androidx.compose.ui.platform.LocalContext.current

    var showMore by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }

    // Recovered state notification
    if (navState.isRecovered) {
        LaunchedEffect(Unit) {
            // Would show a snackbar: "WZNOWIONO"
        }
    }

    // Synchronize UI switch with persistence completion
    LaunchedEffect(isStopping, recordingSession) {
        if (isStopping && recordingSession == null) {
            android.util.Log.d("StopTrace", "SWITCHING_TO_POST_RIDE_VIA_DB_SYNC=true")
            appModeManager.switchToPostRide()
        }
    }

    // Safety fallback: if DB takes too long or service is dead, force exit after 3 seconds
    LaunchedEffect(isStopping) {
        if (isStopping) {
            kotlinx.coroutines.delay(3000L)
            android.util.Log.d("StopTrace", "SWITCHING_TO_POST_RIDE_VIA_TIMEOUT=true")
            appModeManager.switchToPostRide()
        }
    }

    val handleStopRide = {
        android.util.Log.d("StopTrace", "HANDLE_STOP_RIDE_ENTERED=true")
        android.util.Log.d("StopTrace", "IS_STOPPING_BEFORE=$isStopping")
        android.util.Log.d("StopTrace", "ACTIVE_RECORDING_SESSION=${recordingSession != null}")
        
        if (!isStopping) {
            isStopping = true
            android.util.Log.d("StopTrace", "TRACK_RECORDING_STOP_REQUESTED=true")
            
            navigationManager.stopNavigation()
            android.util.Log.d("StopTrace", "NAVIGATION_STOPPED=true")
            
            val intent = android.content.Intent(context, pl.mazovia.offroad.service.TrackRecordingService::class.java).apply {
                action = pl.mazovia.offroad.service.TrackRecordingService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    if (isLandscape) {
        LandscapeRidingLayout(
            navState = navState,
            recordingSession = recordingSession,
            onMore = { showMore = true },
            onStopNavigation = { handleStopRide() }
        )
    } else {
        PortraitRidingLayout(
            navState = navState,
            recordingSession = recordingSession,
            onMore = { showMore = true },
            onStopNavigation = { handleStopRide() }
        )
    }

    // More menu
    if (showMore) {
        RidingMoreDialog(
            onDismiss = { showMore = false },
            onStopNavigation = {
                showMore = false
                handleStopRide()
            }
        )
    }
}

@Composable
private fun PortraitRidingLayout(
    navState: pl.mazovia.offroad.domain.model.NavigationState,
    recordingSession: pl.mazovia.offroad.data.db.entity.RecordingSessionEntity?,
    onMore: () -> Unit,
    onStopNavigation: () -> Unit
) {
    var centerRequest by remember { mutableLongStateOf(0L) }
    var isFollowMode by remember { mutableStateOf(true) }

    LaunchedEffect(navState.currentPosition) {
        if (centerRequest == 0L && navState.currentPosition != null) {
            centerRequest = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MazoviaColors.RidingBackground)
            .systemBarsPadding()
            .padding(12.dp)
    ) {
        // TOP 1: Status Pills
        RidingStatusPills(
            hasGpsFix = navState.currentPosition != null,
            isRecording = recordingSession != null,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // TOP 2: Maneuver Card
        val nextManeuver = navState.nextManeuver
        if (nextManeuver != null) {
            val maneuverIndex = navState.route?.maneuvers?.indexOf(nextManeuver) ?: -1
            val nextNextManeuver = if (maneuverIndex != -1 && maneuverIndex + 1 < (navState.route?.maneuvers?.size ?: 0)) {
                navState.route?.maneuvers?.get(maneuverIndex + 1)
            } else null
            
            val nextNextText = nextNextManeuver?.let {
                // The distance to the next-next maneuver is the distance of the CURRENT nextManeuver
                val dist = nextManeuver.distanceMeters
                val distKm = dist / 1000.0
                if (distKm >= 1.0) String.format("%.1f km", distKm) else "${dist.toInt()} m"
            }

            ManeuverView(
                maneuverType = nextManeuver.type.name,
                distanceMeters = navState.distanceToNextManeuverMeters,
                streetName = nextManeuver.streetName,
                nextManeuverText = nextNextText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(84.dp)) // Placeholder if no maneuver
        }

        // TOP 3: Waypoint Info Row
        val waypoints = navState.route?.waypoints
        if (!waypoints.isNullOrEmpty()) {
            // Find next waypoint ahead
            val nextWpIndex = waypoints.indexOfFirst { wp -> 
                navState.currentPosition?.let { pos -> wp.distanceTo(pos) > 50.0 } ?: true
            }
            if (nextWpIndex != -1) {
                val nextWp = waypoints[nextWpIndex]
                val distToWp = navState.currentPosition?.distanceTo(nextWp) ?: 0.0
                val distStr = if (distToWp >= 1000) String.format("%.1f km", distToWp / 1000) else "${distToWp.toInt()} m"
                
                Row(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .border(1.dp, MazoviaColors.RidingBorder, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(MazoviaColors.RidingCardBackground)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF00E5FF)) // Cyan dot
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "WP${nextWpIndex + 1} • $distStr",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // CENTER: Map
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .border(1.dp, MazoviaColors.RidingBorder, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        ) {
            val routeId = navState.route?.id
            val routePoints = remember(routeId) {
                navState.route?.allPoints ?: emptyList()
            }
            
            // POC Opt-in toggle:
            val mapEngine = MapEngine.MAPLIBRE_PMTILES_POC // Default OSMDROID, testing MapLibre for POC
            
            when (mapEngine) {
                MapEngine.MAPLIBRE_PMTILES_POC -> {
                    pl.mazovia.offroad.ui.map.components.MapLibrePMTilesPOCContainer(
                        currentPosition = navState.currentPosition,
                        destination = navState.route?.destination,
                        routePoints = routePoints,
                        centerRequest = centerRequest,
                        onLongPress = { },
                        isFollowMode = isFollowMode,
                        bearing = navState.currentBearing,
                        onUserPan = { isFollowMode = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MapEngine.MAPLIBRE -> {
                    MapLibreViewContainer(
                        currentPosition = navState.currentPosition,
                        destination = navState.route?.destination,
                        routePoints = routePoints,
                        centerRequest = centerRequest,
                        onLongPress = { },
                        isFollowMode = isFollowMode,
                        bearing = navState.currentBearing,
                        onUserPan = { isFollowMode = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MapEngine.OSMDROID -> {
                    MapViewContainer(
                        currentPosition = navState.currentPosition,
                        destination = navState.route?.destination,
                        routePoints = routePoints,
                        centerRequest = centerRequest,
                        onLongPress = { },
                        isFollowMode = isFollowMode,
                        bearing = navState.currentBearing,
                        onUserPan = { isFollowMode = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Map Controls (Right edge)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.IconButton(
                    onClick = { 
                        isFollowMode = true
                        val newRequest = System.currentTimeMillis()
                        android.util.Log.d("RecenterTrace", "RECENTER_CLICKED=true")
                        android.util.Log.d("RecenterTrace", "RECENTER_CURRENT_POSITION_PRESENT=${navState.currentPosition != null}")
                        android.util.Log.d("RecenterTrace", "RECENTER_CURRENT_LAT=${navState.currentPosition?.latitude}")
                        android.util.Log.d("RecenterTrace", "RECENTER_CURRENT_LON=${navState.currentPosition?.longitude}")
                        android.util.Log.d("RecenterTrace", "RECENTER_REQUEST_BEFORE=$centerRequest")
                        android.util.Log.d("RecenterTrace", "RECENTER_REQUEST_AFTER=$newRequest")
                        centerRequest = newRequest 
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(MazoviaColors.RidingCardBackground.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape)
                        .border(1.dp, MazoviaColors.RidingBorder, androidx.compose.foundation.shape.CircleShape)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                        contentDescription = "Center",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Column(
                    modifier = Modifier
                        .background(MazoviaColors.RidingCardBackground.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .border(1.dp, MazoviaColors.RidingBorder, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { /* zoom in */ },
                        modifier = Modifier.size(40.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Add,
                            contentDescription = "Zoom In",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(color = MazoviaColors.RidingBorder, modifier = Modifier.width(40.dp))
                    androidx.compose.material3.IconButton(
                        onClick = { /* zoom out */ },
                        modifier = Modifier.size(40.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Remove,
                            contentDescription = "Zoom Out",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // BOTTOM 1: Terrain Radar Bar
        navState.route?.let { route ->
            val calculator = remember { pl.mazovia.offroad.routing.terrain.TerrainRadarCalculator(lookAheadMeters = 10000.0) } // 10km like v7
            val radar = calculator.calculate(route, navState.currentSegmentIndex)
            
            if (radar.segments.isNotEmpty()) {
                val uiSegments = radar.segments.map {
                    TerrainRadarSegmentUi(
                        fraction = it.fraction,
                        color = it.surface.toRadarColor(),
                        label = it.surface.toRadarLabel(),
                        distanceLabel = if (it.distanceMeters >= 1000) String.format("%.1fk", it.distanceMeters/1000) else "${it.distanceMeters.toInt()}m"
                    )
                }
                TerrainRadarView(
                    segments = uiSegments,
                    offRoadProportion = radar.offRoadProportion,
                    asphaltConnectorKm = radar.asphaltConnectorLengthMeters?.let { it / 1000.0 },
                    confidenceLabel = radar.dataConfidence.name,
                    lookAheadKm = radar.lookAheadMeters / 1000.0,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }
        }

        // BOTTOM 2: Data Panel
        val etaTime = navState.remainingTimeSeconds?.let {
            java.time.LocalTime.now().plusSeconds(it).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
        
        RidingDataPanel(
            speedKmh = navState.currentSpeedMps?.let { (it * 3.6).toInt() },
            remainingKm = navState.remainingDistanceMeters?.let { it / 1000.0 },
            etaText = etaTime,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // BOTTOM 3: Action Buttons
        RidingActionButtons(
            onSkipWaypoint = { /* TODO later */ },
            onStopRide = {
                android.util.Log.d("StopTrace", "ACTION_BUTTON_ON_STOP_RECEIVED=true")
                onStopNavigation()
            },
            modifier = Modifier.padding(bottom = 0.dp)
        )
    }
}

@Composable
private fun LandscapeRidingLayout(
    navState: pl.mazovia.offroad.domain.model.NavigationState,
    recordingSession: pl.mazovia.offroad.data.db.entity.RecordingSessionEntity?,
    onMore: () -> Unit,
    onStopNavigation: () -> Unit
) {
    var centerRequest by remember { mutableLongStateOf(0L) }
    var isFollowMode by remember { mutableStateOf(true) }

    LaunchedEffect(navState.currentPosition) {
        if (centerRequest == 0L && navState.currentPosition != null) {
            centerRequest = System.currentTimeMillis()
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // LEFT: Guidance area
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Maneuver
            navState.nextManeuver?.let { maneuver ->
                ManeuverView(
                    maneuverType = maneuver.type.name,
                    distanceMeters = navState.distanceToNextManeuverMeters,
                    streetName = maneuver.streetName,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Data row
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = navState.currentSpeedMps?.let { "${(it * 3.6).toInt()} km/h" } ?: "-- km/h",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Pozostało: ${formatDistance(navState.remainingDistanceMeters)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Trip: ${recordingSession?.let { formatDistance(it.distanceMeters) } ?: "--"}",
                    style = MaterialTheme.typography.titleMedium
                )
                RidingActionButton(
                    text = "WIĘCEJ",
                    onClick = onMore,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // RIGHT: Map
        Box(modifier = Modifier
            .weight(1f)
            .fillMaxHeight()) {
            val routeId = navState.route?.id
            val routePoints = remember(routeId) {
                navState.route?.allPoints ?: emptyList()
            }
            
            // POC Opt-in toggle:
            val mapEngine = MapEngine.MAPLIBRE_PMTILES_POC // Default OSMDROID, testing MapLibre for POC
            
            when (mapEngine) {
                MapEngine.MAPLIBRE_PMTILES_POC -> {
                    pl.mazovia.offroad.ui.map.components.MapLibrePMTilesPOCContainer(
                        currentPosition = navState.currentPosition,
                        destination = navState.route?.destination,
                        routePoints = routePoints,
                        centerRequest = centerRequest,
                        onLongPress = { },
                        isFollowMode = isFollowMode,
                        bearing = navState.currentBearing,
                        onUserPan = { isFollowMode = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MapEngine.MAPLIBRE -> {
                    MapLibreViewContainer(
                        currentPosition = navState.currentPosition,
                        destination = navState.route?.destination,
                        routePoints = routePoints,
                        centerRequest = centerRequest,
                        onLongPress = { },
                        isFollowMode = isFollowMode,
                        bearing = navState.currentBearing,
                        onUserPan = { isFollowMode = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MapEngine.OSMDROID -> {
                    MapViewContainer(
                        currentPosition = navState.currentPosition,
                        destination = navState.route?.destination,
                        routePoints = routePoints,
                        centerRequest = centerRequest,
                        onLongPress = { },
                        isFollowMode = isFollowMode,
                        bearing = navState.currentBearing,
                        onUserPan = { isFollowMode = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Recenter Button in Landscape
            androidx.compose.material3.IconButton(
                onClick = {
                    isFollowMode = true
                    centerRequest = System.currentTimeMillis()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .background(MazoviaColors.RidingCardBackground.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape)
                    .border(1.dp, MazoviaColors.RidingBorder, androidx.compose.foundation.shape.CircleShape)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.MyLocation,
                    contentDescription = "Center",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Terrain Radar Overlay
            navState.route?.let { route ->
                val calculator = remember { pl.mazovia.offroad.routing.terrain.TerrainRadarCalculator() }
                val radar = calculator.calculate(route, navState.currentSegmentIndex)
                
                if (radar.segments.isNotEmpty()) {
                    val uiSegments = radar.segments.map {
                        TerrainRadarSegmentUi(
                            fraction = it.fraction,
                            color = it.surface.toRadarColor(),
                            label = it.surface.toRadarLabel(),
                            distanceLabel = if (it.distanceMeters >= 1000) String.format("%.1fk", it.distanceMeters/1000) else "${it.distanceMeters.toInt()}m"
                        )
                    }
                    TerrainRadarView(
                        segments = uiSegments,
                        offRoadProportion = radar.offRoadProportion,
                        asphaltConnectorKm = radar.asphaltConnectorLengthMeters?.let { it / 1000.0 },
                        confidenceLabel = radar.dataConfidence.name,
                        lookAheadKm = radar.lookAheadMeters / 1000.0,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .width(200.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RidingMoreDialog(
    onDismiss: () -> Unit,
    onStopNavigation: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Więcej") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MazoviaButton(
                    text = "Zakończ jazdę",
                    onClick = onStopNavigation,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zamknij")
            }
        }
    )
}

private fun formatDistance(meters: Double?): String {
    if (meters == null) return "--"
    return when {
        meters >= 1000 -> String.format("%.1f km", meters / 1000)
        else -> String.format("%.0f m", meters)
    }
}

private fun pl.mazovia.offroad.domain.model.Surface.toRadarColor(): androidx.compose.ui.graphics.Color {
    return when (this) {
        pl.mazovia.offroad.domain.model.Surface.ASPHALT,
        pl.mazovia.offroad.domain.model.Surface.CONCRETE,
        pl.mazovia.offroad.domain.model.Surface.PAVED -> MazoviaColors.TerrainRadarRed
        pl.mazovia.offroad.domain.model.Surface.DIRT,
        pl.mazovia.offroad.domain.model.Surface.EARTH,
        pl.mazovia.offroad.domain.model.Surface.GROUND -> MazoviaColors.TerrainRadarGreen
        pl.mazovia.offroad.domain.model.Surface.MUD -> MazoviaColors.TerrainRadarGreen
        pl.mazovia.offroad.domain.model.Surface.GRAVEL,
        pl.mazovia.offroad.domain.model.Surface.FINE_GRAVEL,
        pl.mazovia.offroad.domain.model.Surface.COMPACTED -> MazoviaColors.TerrainRadarYellow
        pl.mazovia.offroad.domain.model.Surface.SAND -> MazoviaColors.TerrainRadarYellow
        else -> androidx.compose.ui.graphics.Color.Gray
    }
}

private fun pl.mazovia.offroad.domain.model.Surface.toRadarLabel(): String {
    return when (this) {
        pl.mazovia.offroad.domain.model.Surface.ASPHALT,
        pl.mazovia.offroad.domain.model.Surface.CONCRETE,
        pl.mazovia.offroad.domain.model.Surface.PAVED -> "ASFALT"
        pl.mazovia.offroad.domain.model.Surface.DIRT,
        pl.mazovia.offroad.domain.model.Surface.EARTH,
        pl.mazovia.offroad.domain.model.Surface.GROUND -> "DUKT"
        pl.mazovia.offroad.domain.model.Surface.MUD -> "BŁOTO"
        pl.mazovia.offroad.domain.model.Surface.GRAVEL,
        pl.mazovia.offroad.domain.model.Surface.FINE_GRAVEL,
        pl.mazovia.offroad.domain.model.Surface.COMPACTED -> "SZUTER"
        pl.mazovia.offroad.domain.model.Surface.SAND -> "PIACH"
        else -> "INNE"
    }
}
