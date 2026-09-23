package pl.mazovia.offroad.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import pl.mazovia.offroad.MazoviaOffroadApp
import pl.mazovia.offroad.domain.model.AppMode
import pl.mazovia.offroad.state.AppModeManager
import pl.mazovia.offroad.ui.map.MapScreen
import pl.mazovia.offroad.ui.rides.RidesScreen
import pl.mazovia.offroad.ui.routes.RoutesScreen
import pl.mazovia.offroad.ui.more.MoreScreen
import pl.mazovia.offroad.ui.riding.RidingScreen
import pl.mazovia.offroad.ui.postride.PostRideScreen

enum class MazoviaTab(
    val label: String,
    val icon: ImageVector
) {
    MAPA("Mapa", Icons.Default.Map),
    TRASY("Trasy", Icons.Default.Route),
    JAZDY("Jazdy", Icons.Default.DirectionsBike),
    WIECEJ("Więcej", Icons.Default.MoreHoriz)
}

@Composable
fun MazoviaNavHost(
    appMode: AppMode,
    appModeManager: AppModeManager,
    app: MazoviaOffroadApp
) {
    when (appMode) {
        AppMode.RIDING -> {
            RidingScreen(
                navigationManager = app.navigationManager,
                appModeManager = appModeManager,
                sessionRepository = app.sessionRepository
            )
        }
        AppMode.POST_RIDE -> {
            PostRideScreen(
                rideRepository = app.rideRepository,
                feedbackRepository = app.feedbackRepository,
                rideId = appModeManager.completedRideId,
                onDismiss = { appModeManager.switchToPlanning() }
            )
        }
        AppMode.PLANNING -> {
            PlanningShell(
                app = app,
                appModeManager = appModeManager
            )
        }
    }
}

@Composable
private fun PlanningShell(
    app: MazoviaOffroadApp,
    appModeManager: AppModeManager
) {
    var selectedTab by remember { mutableStateOf(MazoviaTab.MAPA) }
    var showOfflineData by remember { mutableStateOf(false) }
    var showCalibrationScreen by remember { mutableStateOf(false) }
    var previewRoute by remember { mutableStateOf<pl.mazovia.offroad.domain.model.Route?>(null) }

    if (showOfflineData) {
        pl.mazovia.offroad.ui.more.OfflineDataScreen(
            routingEngine = app.routingEngine,
            onBack = { showOfflineData = false }
        )
        return
    }
    
    if (showCalibrationScreen) {
        pl.mazovia.offroad.ui.riding.CalibrationScreen(
            app = app,
            onBack = { showCalibrationScreen = false }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MazoviaTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                MazoviaTab.MAPA -> MapScreen(
                    routingEngine = app.routingEngine,
                    navigationManager = app.navigationManager,
                    appModeManager = appModeManager,
                    locationClient = app.locationClient,
                    placeSearchRepository = app.placeSearchRepository,
                    routeRepository = app.routeRepository,
                    onNavigateToOfflineData = { showOfflineData = true },
                    previewRoute = previewRoute,
                    onPreviewConsumed = { previewRoute = null }
                )
                MazoviaTab.TRASY -> RoutesScreen(
                    routingEngine = app.routingEngine,
                    routeRepository = app.routeRepository,
                    navigationManager = app.navigationManager,
                    appModeManager = appModeManager,
                    locationClient = app.locationClient,
                    onPointToPoint = {
                        appModeManager.switchToPlanning()
                        selectedTab = MazoviaTab.MAPA
                    },
                    onOpenRoute = {
                        previewRoute = it
                        appModeManager.switchToPlanning()
                        selectedTab = MazoviaTab.MAPA
                    },
                    onNavigateToOfflineData = { showOfflineData = true }
                )
                MazoviaTab.JAZDY -> RidesScreen(
                    rideRepository = app.rideRepository,
                    feedbackRepository = app.feedbackRepository,
                    onOpenRide = { appModeManager.switchToPostRide(it) }
                )
                MazoviaTab.WIECEJ -> MoreScreen(
                    routingEngine = app.routingEngine,
                    onNavigateToOfflineData = { showOfflineData = true },
                    onNavigateToCalibration = { showCalibrationScreen = true },
                    app = app
                )
            }
        }
    }
}
