package pl.mazovia.offroad

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import pl.mazovia.offroad.data.db.MazoviaDatabase
import pl.mazovia.offroad.data.repository.*
import pl.mazovia.offroad.navigation.NavigationManager
import pl.mazovia.offroad.routing.engine.GraphHopperRoutingEngine
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Application class - initializes core dependencies.
 * Simple manual DI for now (can migrate to Hilt later).
 */
class MazoviaOffroadApp : Application() {

    lateinit var database: MazoviaDatabase
        private set
    lateinit var routingEngine: GraphHopperRoutingEngine
        private set
    lateinit var navigationManager: NavigationManager
        private set
    lateinit var rideRepository: RideRepository
        private set
    lateinit var sessionRepository: SessionRepository
        private set
    lateinit var explorationRepository: ExplorationRepository
        private set
    lateinit var feedbackRepository: FeedbackRepository
        private set
    lateinit var routeRepository: RouteRepository
        private set
    lateinit var locationClient: pl.mazovia.offroad.domain.location.LocationClient
        private set
    lateinit var placeSearchRepository: pl.mazovia.offroad.domain.search.PlaceSearchRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Location
        locationClient = pl.mazovia.offroad.location.AndroidLocationClient(this)

        // Database
        database = MazoviaDatabase.getInstance(this)

        // Repositories
        rideRepository = RideRepository(database.rideDao(), database.trackPointDao())
        sessionRepository = SessionRepository(database.sessionDao())
        explorationRepository = ExplorationRepository(database.riddenSegmentDao())
        feedbackRepository = FeedbackRepository(database.roadFeedbackDao())
        routeRepository = RouteRepository(database.savedRouteDao())
        placeSearchRepository = pl.mazovia.offroad.data.search.NominatimPlaceSearchRepository()

        // Routing
        routingEngine = GraphHopperRoutingEngine()
        
        // Load offline graph asynchronously if present
        kotlinx.coroutines.GlobalScope.launch {
            val graphPath = java.io.File(filesDir, "graph").absolutePath
            routingEngine.loadGraph(graphPath)
        }

        // Navigation
        navigationManager = NavigationManager(routingEngine, locationClient)

        // Notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val recordingChannel = NotificationChannel(
                RECORDING_CHANNEL_ID,
                "Nagrywanie trasy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Powiadomienie o aktywnym nagrywaniu trasy"
                setShowBadge(false)
            }

            val navigationChannel = NotificationChannel(
                NAVIGATION_CHANNEL_ID,
                "Nawigacja",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Powiadomienia nawigacyjne"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(recordingChannel)
            manager.createNotificationChannel(navigationChannel)
        }
    }

    companion object {
        const val RECORDING_CHANNEL_ID = "recording_channel"
        const val NAVIGATION_CHANNEL_ID = "navigation_channel"
    }
}
