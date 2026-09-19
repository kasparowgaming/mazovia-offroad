package pl.mazovia.offroad.routing.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.Instruction
import com.graphhopper.util.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException


import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.*
import pl.mazovia.offroad.routing.profile.OffroadProfileConfig
import java.io.File
import java.util.UUID

/**
 * Real GraphHopper routing engine implementation.
 * Targets offline local routing with memory-efficient graph access.
 */
class GraphHopperRoutingEngine : RoutingEngine {

    private var graphHopper: GraphHopper? = null
    private var currentGraphPath: String? = null
    private var lastError: String? = null
    
    // Safety mutex to ensure graph swapping/unloading does not race with active routing
    private val engineMutex = Mutex()
    
    // Visible for testing / benchmarking
    internal var benchmarkListener: BenchmarkListener? = null

    internal interface BenchmarkListener {
        fun onCandidateEvaluated(route: Route?, isBaseline: Boolean, profile: RoutingProfile, elapsedMs: Long, error: String? = null)
        fun onTournamentStarted(baselineDistance: Double, limitPercent: Double, candidateCount: Int)
        fun onTournamentFinished(winner: Route?)
    }
    
    


    override suspend fun isReady(): Boolean = graphHopper != null

    override suspend fun getState(): RoutingEngineState {
        val gh = graphHopper
        return if (gh != null) {
            RoutingEngineState(
                isGraphLoaded = true,
                graphPath = currentGraphPath,
                graphVersion = "GH-9.1",
                nodeCount = gh.baseGraph.nodes.toLong(),
                edgeCount = gh.baseGraph.edges.toLong(),
                memoryUsageBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                supportedProfiles = RoutingProfile.entries.toList(),
                lastError = lastError
            )
        } else {
            RoutingEngineState(
                isGraphLoaded = false,
                graphPath = currentGraphPath,
                lastError = lastError
            )
        }
    }

    private fun initGraphHopper(graphPath: String): GraphHopper {
        val graphDir = File(graphPath)
        if (!graphDir.exists()) {
            throw IllegalArgumentException("Katalog z grafem nie istnieje.")
        }

        // Basic sanity check: look for geometry or properties file
        if (graphDir.listFiles()?.none { it.name.contains("geometry") || it.name.contains("properties") } == true) {
            throw IllegalArgumentException("Brak wymaganych plików GraphHopper w wybranym folderze.")
        }

        val hopper = AndroidGraphHopper().apply {
            init(com.graphhopper.GraphHopperConfig()
                .putObject("graph.location", graphPath)
                .putObject("graph.dataaccess.default_type", "RAM_STORE")
                .putObject("import.osm.ignored_highways", ""))
            setAllowWrites(false)
        }
        
        hopper.setProfiles(
            Profile("enduro_normal").setCustomModel(buildCustomModel(RoutingProfile.BEZPIECZNY)),
            Profile("enduro_max").setCustomModel(buildCustomModel(RoutingProfile.TERENOWY)),
            Profile("enduro_extreme").setCustomModel(buildCustomModel(RoutingProfile.ODKRYWCZY))
        )
        
        hopper.importOrLoad()
        return hopper
    }

    private fun buildCustomModel(mode: RoutingProfile): com.graphhopper.util.CustomModel {
        val model = com.graphhopper.util.CustomModel()

        model.addToSpeed(com.graphhopper.json.Statement.If("true", com.graphhopper.json.Statement.Op.LIMIT, "enduro_average_speed"))
        model.addToPriority(com.graphhopper.json.Statement.If("enduro_access == false", com.graphhopper.json.Statement.Op.MULTIPLY, "0"))

        val majorMult = when (mode) {
            RoutingProfile.BEZPIECZNY -> "0.08"
            RoutingProfile.TERENOWY -> "0.06"
            RoutingProfile.ODKRYWCZY -> "0.05"
        }
        model.addToPriority(
            com.graphhopper.json.Statement.If(
                "road_class == MOTORWAY || road_class == TRUNK || road_class == PRIMARY",
                com.graphhopper.json.Statement.Op.MULTIPLY, majorMult
            )
        )

        val pavedMult = when (mode) {
            RoutingProfile.BEZPIECZNY -> "0.20"
            RoutingProfile.TERENOWY -> "0.12"
            RoutingProfile.ODKRYWCZY -> "0.10"
        }
        model.addToPriority(
            com.graphhopper.json.Statement.If(
                "surface == ASPHALT || surface == CONCRETE || surface == PAVING_STONES || surface == COBBLESTONE",
                com.graphhopper.json.Statement.Op.MULTIPLY, pavedMult
            )
        )

        when (mode) {
            RoutingProfile.BEZPIECZNY -> {
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE1", com.graphhopper.json.Statement.Op.MULTIPLY, "0.8"))
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE2 || track_type == GRADE3", com.graphhopper.json.Statement.Op.MULTIPLY, "1.1"))
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE4 || track_type == GRADE5", com.graphhopper.json.Statement.Op.MULTIPLY, "0.9"))
            }
            RoutingProfile.TERENOWY -> {
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE1", com.graphhopper.json.Statement.Op.MULTIPLY, "0.6"))
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE2", com.graphhopper.json.Statement.Op.MULTIPLY, "1.0"))
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE3 || track_type == GRADE4", com.graphhopper.json.Statement.Op.MULTIPLY, "1.3"))
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE5", com.graphhopper.json.Statement.Op.MULTIPLY, "1.2"))
            }
            RoutingProfile.ODKRYWCZY -> {
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE1 || track_type == GRADE2", com.graphhopper.json.Statement.Op.MULTIPLY, "0.5"))
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE3", com.graphhopper.json.Statement.Op.MULTIPLY, "1.2"))
                model.addToPriority(com.graphhopper.json.Statement.If("track_type == GRADE4 || track_type == GRADE5", com.graphhopper.json.Statement.Op.MULTIPLY, "1.6"))
            }
        }

        model.addToPriority(com.graphhopper.json.Statement.If("smoothness == IMPASSABLE", com.graphhopper.json.Statement.Op.MULTIPLY, "0"))
        when (mode) {
            RoutingProfile.BEZPIECZNY -> {
                model.addToPriority(com.graphhopper.json.Statement.If("smoothness == VERY_HORRIBLE || smoothness == HORRIBLE", com.graphhopper.json.Statement.Op.MULTIPLY, "0.5"))
                model.addToPriority(com.graphhopper.json.Statement.If("smoothness == VERY_BAD", com.graphhopper.json.Statement.Op.MULTIPLY, "0.8"))
            }
            RoutingProfile.TERENOWY -> {
                model.addToPriority(com.graphhopper.json.Statement.If("smoothness == HORRIBLE || smoothness == VERY_HORRIBLE", com.graphhopper.json.Statement.Op.MULTIPLY, "1.3"))
                model.addToPriority(com.graphhopper.json.Statement.If("smoothness == VERY_BAD", com.graphhopper.json.Statement.Op.MULTIPLY, "1.1"))
            }
            RoutingProfile.ODKRYWCZY -> {
                model.addToPriority(com.graphhopper.json.Statement.If("smoothness == VERY_HORRIBLE", com.graphhopper.json.Statement.Op.MULTIPLY, "1.6"))
                model.addToPriority(com.graphhopper.json.Statement.If("smoothness == HORRIBLE", com.graphhopper.json.Statement.Op.MULTIPLY, "1.4"))
                model.addToPriority(com.graphhopper.json.Statement.If("smoothness == VERY_BAD", com.graphhopper.json.Statement.Op.MULTIPLY, "1.2"))
            }
        }

        val missing = "surface == MISSING && track_type == MISSING && "
        val trMult = when (mode) { RoutingProfile.BEZPIECZNY -> "0.9"; RoutingProfile.TERENOWY -> "1.12"; RoutingProfile.ODKRYWCZY -> "1.25" }
        model.addToPriority(com.graphhopper.json.Statement.If(missing + "road_class == TRACK", com.graphhopper.json.Statement.Op.MULTIPLY, trMult))
        
        val pMult = when (mode) { RoutingProfile.BEZPIECZNY -> "0.8"; RoutingProfile.TERENOWY -> "1.05"; RoutingProfile.ODKRYWCZY -> "1.15" }
        model.addToPriority(com.graphhopper.json.Statement.If(missing + "road_class == PATH", com.graphhopper.json.Statement.Op.MULTIPLY, pMult))
        
        val sMult = when (mode) { RoutingProfile.BEZPIECZNY -> "1.0"; RoutingProfile.TERENOWY -> "1.02"; RoutingProfile.ODKRYWCZY -> "1.02" }
        model.addToPriority(com.graphhopper.json.Statement.If(missing + "road_class == SERVICE", com.graphhopper.json.Statement.Op.MULTIPLY, sMult))
        
        model.addToPriority(com.graphhopper.json.Statement.If(missing + "road_class == UNCLASSIFIED", com.graphhopper.json.Statement.Op.MULTIPLY, "1.0"))

        return model
    }

    override suspend fun loadGraph(graphPath: String): Boolean = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            try {
                unloadGraphInternal()
                
                val hopper = initGraphHopper(graphPath)
                
                graphHopper = hopper
                currentGraphPath = graphPath
                lastError = null
                true
            } catch (e: Exception) {
                lastError = "Failed to load graph: ${e.message}"
                false
            }
        }
    }

    override suspend fun validateAndSwapGraph(tempGraphPath: String): RoutingEngine.ImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(tempGraphPath)
        try {
            // 1. Validate the temp graph outside the lock to not block routing too long
            val tempHopper = initGraphHopper(tempGraphPath)
            tempHopper.close() // Close it immediately after validation succeeds
            
            engineMutex.withLock {
                // 2. Unload active graph
                unloadGraphInternal()
                
                // 3. Swap directories
                val activeDir = File(tempDir.parentFile, "graph")
                if (activeDir.exists()) {
                    activeDir.deleteRecursively()
                }
                tempDir.renameTo(activeDir)
                
                // 4. Load the new active graph
                val hopper = initGraphHopper(activeDir.absolutePath)
                graphHopper = hopper
                currentGraphPath = activeDir.absolutePath
                lastError = null
            }
            
            RoutingEngine.ImportResult.Success
        } catch (e: Exception) {
            // Clean up temp dir on failure
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            RoutingEngine.ImportResult.Error(e.message ?: "Nieznany błąd podczas weryfikacji grafu.")
        }
    }

    override suspend fun unloadGraph() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            unloadGraphInternal()
        }
    }

    private fun unloadGraphInternal() {
        try {
            graphHopper?.close()
        } catch (_: Exception) {}
        graphHopper = null
    }

    override suspend fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        waypoints: List<GeoPoint>
    ): RoutingResult = withContext(Dispatchers.IO) {
        val gh = graphHopper ?: return@withContext RoutingResult.Error(RoutingError.GRAPH_NOT_LOADED)

        // Base route calculation (not speculative)
        val baselineResult = calculateSingleRoute(origin, destination, profile, waypoints, isSpeculative = false)
        
        // If there are specific waypoints or if profile is BEZPIECZNY, just return the baseline
        if (waypoints.isNotEmpty() || profile == RoutingProfile.BEZPIECZNY) {
            return@withContext baselineResult
        }

        // We only proceed with geometric alternatives if baseline succeeds
        val baselineRoute = (baselineResult as? RoutingResult.Success)?.route ?: return@withContext baselineResult

        try {
            val corridors = RouteTournament.profileDiversityCorridors(origin, destination, profile)
            if (corridors.isEmpty()) {
                return@withContext baselineResult
            }

            android.util.Log.e("GraphHopperDiagnostic", "Generating ${corridors.size} alternative corridors sequentially")
            
            val candidates = mutableListOf<Route>()
            for (corridor in corridors) {
                try {
                    val result = calculateSingleRoute(origin, destination, profile, corridor, isSpeculative = true)
                    val route = (result as? RoutingResult.Success)?.route
                    if (route != null) {
                        candidates.add(route)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Skip failed speculative candidate
                }
            }

            candidates.add(baselineRoute)
            
            android.util.Log.e("GraphHopperDiagnostic", "Tournament evaluating ${candidates.size} valid candidates")
            val limit = RouteTournament.profileDetourLimit(profile)
            try {
                benchmarkListener?.onTournamentStarted(baselineRoute.totalDistanceMeters, limit, candidates.size)
            } catch (e: Exception) {
                android.util.Log.e("GraphHopperDiagnostic", "BenchmarkListener failed", e)
            }
            val winner = RouteTournament.chooseTournamentWinner(candidates, baselineRoute.totalDistanceMeters, profile)
            try {
                benchmarkListener?.onTournamentFinished(winner)
            } catch (e: Exception) {
                android.util.Log.e("GraphHopperDiagnostic", "BenchmarkListener failed", e)
            }

            if (winner != null) {
                return@withContext RoutingResult.Success(winner)
            }
            return@withContext baselineResult
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                benchmarkListener?.onTournamentFinished(null)
            } catch (ignored: Exception) {}
            android.util.Log.e("GraphHopperDiagnostic", "Tournament failed, falling back to baseline", e)
            return@withContext baselineResult
        }
    }

    private suspend fun calculateSingleRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        waypoints: List<GeoPoint>,
        isSpeculative: Boolean = false
    ): RoutingResult = withContext(Dispatchers.IO) {
        val gh = graphHopper ?: return@withContext RoutingResult.Error(RoutingError.GRAPH_NOT_LOADED)

        val startTime = System.nanoTime()
        var errorMsg: String? = null
        var resultRoute: Route? = null
        
        val result = try {
            android.util.Log.e("GraphHopperDiagnostic", "ROUTE_CALCULATION_START")
            val profileName = profile.toGraphHopperProfile()
            android.util.Log.e("GraphHopperDiagnostic", "PROFILE_SELECTED: " + profileName)
            
            val request = GHRequest().apply {
                addPoint(com.graphhopper.util.shapes.GHPoint(origin.latitude, origin.longitude))
                waypoints.forEach { wp ->
                    addPoint(com.graphhopper.util.shapes.GHPoint(wp.latitude, wp.longitude))
                }
                addPoint(com.graphhopper.util.shapes.GHPoint(destination.latitude, destination.longitude))
                
                this.profile = profileName
                this.locale = java.util.Locale("pl")
                putHint(Parameters.Routing.INSTRUCTIONS, true)
                this.setPathDetails(listOf("surface", "road_class", "track_type"))
                putHint("ch.disable", true)
            }
            android.util.Log.e("GraphHopperDiagnostic", "GHREQUEST_CREATED")

            android.util.Log.e("GraphHopperDiagnostic", "HOPPER_ROUTE_START")
            
            val response = engineMutex.withLock {
                gh.route(request)
            }
            
            android.util.Log.e("GraphHopperDiagnostic", "HOPPER_ROUTE_RETURNED")

            if (response.hasErrors()) {
                val err = response.errors.joinToString { it.message ?: "Unknown error" }
                errorMsg = err
                android.util.Log.e("GraphHopperDiagnostic", "ROUTE ERROR: " + err)
                if (!isSpeculative) {
                    lastError = err
                }
                when {
                    err.contains("Cannot find point", ignoreCase = true) ->
                        RoutingResult.Error(RoutingError.POINT_NOT_FOUND)
                    err.contains("Connection between locations not found", ignoreCase = true) ->
                        RoutingResult.Error(RoutingError.NO_ROUTE_FOUND)
                    else -> RoutingResult.Error(RoutingError.CALCULATION_ERROR)
                }
            } else {

                android.util.Log.e("GraphHopperDiagnostic", "RESPONSE_PATH_COUNT: " + response.all.size)
                val best = response.best
                
                android.util.Log.e("GraphHopperDiagnostic", "ROUTE_EXTRACTION_START")
                val route = convertToRoute(best, origin, destination, profile, stripSyntheticWaypoints = isSpeculative)
                android.util.Log.e("GraphHopperDiagnostic", "ROUTE_EXTRACTION_DONE")
                
                android.util.Log.e("GraphHopperDiagnostic", "ROUTE_METRICS_START")
                android.util.Log.e("GraphHopperDiagnostic", "ROUTE_METRICS_DONE")
                
                resultRoute = route
                RoutingResult.Success(route)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("GraphHopperDiagnostic", "OOM in calculateSingleRoute", e)
            errorMsg = "OOM: ${e.message}"
            if (!isSpeculative) lastError = errorMsg
            RoutingResult.Error(RoutingError.MEMORY_ERROR)
        } catch (e: Exception) {
            android.util.Log.e("GraphHopperDiagnostic", "FATAL EXCEPTION in calculateSingleRoute", e)
            errorMsg = "Exception: ${e.message}"
            if (!isSpeculative) lastError = "Routing error: " + e.message
            RoutingResult.Error(RoutingError.CALCULATION_ERROR)
        }
        
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        try {
            benchmarkListener?.onCandidateEvaluated(resultRoute, !isSpeculative, profile, elapsedMs, errorMsg)
        } catch (e: Exception) {
            android.util.Log.e("GraphHopperDiagnostic", "BenchmarkListener failed", e)
        }
        
        result
    }

    override suspend fun calculateAlternatives(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        maxAlternatives: Int
    ): List<RoutingResult> = withContext(Dispatchers.IO) {
        val gh = graphHopper ?: return@withContext listOf(RoutingResult.Error(RoutingError.GRAPH_NOT_LOADED))

        try {
            val profileName = profile.toGraphHopperProfile()
            val request = GHRequest(
                origin.latitude, origin.longitude,
                destination.latitude, destination.longitude
            ).apply {
                this.profile = profileName
                putHint(Parameters.Routing.INSTRUCTIONS, true)
                this.setPathDetails(listOf("surface", "road_class", "track_type"))
                setAlgorithm(Parameters.Algorithms.ALT_ROUTE)
                putHint("alternative_route.max_paths", maxAlternatives)
                putHint("alternative_route.max_share", 0.6)
                putHint("ch.disable", true)
            }

            val response = engineMutex.withLock {
                gh.route(request)
            }
            
            if (response.hasErrors()) {
                val errorMsg = response.errors.firstOrNull()?.message ?: "Unknown error"
                lastError = errorMsg
                return@withContext listOf(RoutingResult.Error(RoutingError.NO_ROUTE_FOUND))
            }
            
            response.all.map { path ->
                val route = convertToRoute(path, origin, destination, profile)
                RoutingResult.Success(route)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "Alternatives error: ${e.message}"
            listOf(RoutingResult.Error(RoutingError.CALCULATION_ERROR))
        }
    }

    override suspend fun generateLoopCandidates(
        params: LoopParameters,
        candidateCount: Int
    ): List<LoopCandidate> = withContext(Dispatchers.IO) {
        val gh = graphHopper ?: return@withContext emptyList()

        val candidates = mutableListOf<LoopCandidate>()
        val targetDistanceM = params.targetDistanceKm * 1000.0

        // Generate loop candidates by routing through intermediate waypoints
        // at different bearings around the start point
        val bearingStep = 360.0 / (candidateCount * 2)
        val waypointDistance = targetDistanceM / 4.0 // Quarter of target distance for waypoint

        for (i in 0 until candidateCount * 2) {
            if (candidates.size >= candidateCount) break

            val bearing = (params.preferredDirection ?: 0.0) + (i * bearingStep)
            val waypointBearing = bearing % 360.0

            // Calculate intermediate waypoint
            val waypoint = calculatePointAtBearing(
                params.startPoint,
                waypointBearing,
                waypointDistance
            )

            try {
                val result = calculateRoute(
                    origin = params.startPoint,
                    destination = params.startPoint,
                    profile = params.profile,
                    waypoints = listOf(waypoint)
                )

                if (result is RoutingResult.Success) {
                    val route = result.route
                    val score = LoopScore.calculate(route.metrics, params.targetDistanceKm)

                    // Filter out routes that are too far from target distance
                    if (score.targetDistanceError < 0.5) {
                        candidates.add(LoopCandidate(route = route, score = score))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Skip failed candidates
            }
        }

        // Sort by overall score and return top candidates
        candidates.sortedByDescending { it.score.overallScore }
            .take(candidateCount)
    }

    override suspend fun recalculateFromPosition(
        currentPosition: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile
    ): RoutingResult {
        return calculateRoute(currentPosition, destination, profile)
    }

    private fun convertToRoute(
        path: ResponsePath,
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        stripSyntheticWaypoints: Boolean = false
    ): Route {
        val points = path.points.map { point ->
            GeoPoint(
                latitude = point.lat,
                longitude = point.lon,
                elevation = if (point.ele.isNaN()) null else point.ele
            )
        }

        // Extract segments with surface information from path details
        val segments = extractSegments(path, points)
        val metrics = RouteMetrics.fromSegments(segments)
        
        var maneuvers = extractManeuvers(path)
        if (stripSyntheticWaypoints) {
            maneuvers = maneuvers.filter { it.type != ManeuverType.WAYPOINT }
        }

        return Route(
            id = UUID.randomUUID().toString(),
            origin = origin,
            destination = destination,
            segments = segments,
            metrics = metrics,
            profile = profile,
            maneuvers = maneuvers
        )
    }

    private fun extractSegments(path: ResponsePath, allPoints: List<GeoPoint>): List<RouteSegment> {
        if (allPoints.size < 2) return emptyList()

        val segments = mutableListOf<RouteSegment>()
        val pathDetails = path.pathDetails

        val isDiagnostic = path.distance > 0 && path.distance < 20000 && allPoints.size > 10 // roughly matching the diagnostic route
        var shouldDiagnose = false

        // Try to get surface details from GraphHopper
        val surfaceDetails = pathDetails["surface"]
        val roadClassDetails = pathDetails["road_class"]
        val trackTypeDetails = pathDetails["track_type"]
        
        if (System.getProperty("MAZOVIA_DIAGNOSE_GH") == "true") {
            shouldDiagnose = true
            println("=== GH PATH DETAILS DUMP ===")
            println("- contains surface: ${surfaceDetails != null}")
            println("- contains road_class: ${roadClassDetails != null}")
            println("- contains track_type: ${trackTypeDetails != null}")
            
            println("- surface ranges count: ${surfaceDetails?.size}")
            println("- road_class ranges count: ${roadClassDetails?.size}")
            println("- track_type ranges count: ${trackTypeDetails?.size}")
            
            println("- unique surface values: ${surfaceDetails?.map { it.value?.toString() }?.toSet()}")
            println("- unique road_class values: ${roadClassDetails?.map { it.value?.toString() }?.toSet()}")
            println("- unique track_type values: ${trackTypeDetails?.map { it.value?.toString() }?.toSet()}")
            
            println("\n- first 20 surface ranges:")
            surfaceDetails?.take(20)?.forEach { 
                println("  [${it.first}..${it.last}]: ${it.value}") 
            }
            
            println("\n- first 20 road_class ranges:")
            roadClassDetails?.take(20)?.forEach { 
                println("  [${it.first}..${it.last}]: ${it.value}") 
            }
            
            println("\n- first 20 track_type ranges:")
            trackTypeDetails?.take(20)?.forEach { 
                println("  [${it.first}..${it.last}]: ${it.value}") 
            }
        }

        if (surfaceDetails != null && surfaceDetails.isNotEmpty()) {
            for (detail in surfaceDetails) {
                val fromIdx = detail.first as Int
                val toIdx = detail.last as Int
                val surfaceTag = detail.value?.toString()

                val segPoints = allPoints.subList(
                    fromIdx.coerceIn(0, allPoints.size - 1),
                    (toIdx + 1).coerceIn(1, allPoints.size)
                )

                if (segPoints.size >= 2) {
                    val distance = segPoints.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
                    val surface = Surface.fromOsmTag(surfaceTag)
                    
                    val rcTag = roadClassDetails?.find { fromIdx >= (it.first as Int) && fromIdx < (it.last as Int) }?.value?.toString()
                    val highway = HighwayType.fromOsmTag(rcTag)
                    
                    val ttTag = trackTypeDetails?.find { fromIdx >= (it.first as Int) && fromIdx < (it.last as Int) }?.value?.toString()
                    val trackType = TrackType.fromOsmTag(ttTag)

                    segments.add(
                        RouteSegment(
                            points = segPoints,
                            distanceMeters = distance,
                            surface = surface,
                            highway = highway,
                            trackType = trackType,
                            dataConfidence = if (surfaceTag != null || rcTag != null) DataConfidence.CONFIRMED
                            else DataConfidence.UNKNOWN
                        )
                    )
                }
            }
        } else {
            // Fallback: single segment with unknown surface
            val distance = allPoints.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
            val rcTag = roadClassDetails?.firstOrNull()?.value?.toString()
            val highway = HighwayType.fromOsmTag(rcTag)
            
            val ttTag = trackTypeDetails?.firstOrNull()?.value?.toString()
            val trackType = TrackType.fromOsmTag(ttTag)
            
            segments.add(
                RouteSegment(
                    points = allPoints,
                    distanceMeters = distance,
                    surface = Surface.UNKNOWN,
                    highway = highway,
                    trackType = trackType,
                    dataConfidence = DataConfidence.UNKNOWN
                )
            )
        }

        return segments
    }

    private fun extractManeuvers(path: ResponsePath): List<Maneuver> {
        return path.instructions.mapNotNull { instruction ->
            val point = instruction.points.firstOrNull() ?: return@mapNotNull null
            Maneuver(
                point = GeoPoint(point.lat, point.lon),
                type = mapInstructionSign(instruction.sign),
                distanceMeters = instruction.distance,
                streetName = instruction.name.takeIf { it.isNotBlank() },
                instruction = maneuverText(instruction.sign, instruction.name) ?: "Jedź prosto"
            )
        }
    }

    private fun maneuverText(sign: Int, roadName: String?): String? {
        val action = when (sign) {
            Instruction.TURN_SHARP_LEFT -> "Ostro w lewo"
            Instruction.TURN_LEFT -> "Skręt w lewo"
            Instruction.TURN_SLIGHT_LEFT -> "Lekko w lewo"
            Instruction.TURN_SHARP_RIGHT -> "Ostro w prawo"
            Instruction.TURN_RIGHT -> "Skręt w prawo"
            Instruction.TURN_SLIGHT_RIGHT -> "Lekko w prawo"
            Instruction.U_TURN_UNKNOWN,
            Instruction.U_TURN_LEFT,
            Instruction.U_TURN_RIGHT -> "Zawróć"
            Instruction.KEEP_LEFT -> "Trzymaj się lewej"
            Instruction.KEEP_RIGHT -> "Trzymaj się prawej"
            Instruction.USE_ROUNDABOUT -> "Wjedź na rondo"
            Instruction.LEAVE_ROUNDABOUT -> "Zjedź z ronda"
            Instruction.FINISH -> "Cel podróży"
            Instruction.REACHED_VIA -> "Punkt pośredni"
            else -> return null
        }
        return roadName?.takeIf { it.isNotBlank() }?.let { "$action w $it" } ?: action
    }

    private fun mapInstructionSign(sign: Int): ManeuverType = when (sign) {
        Instruction.TURN_LEFT -> ManeuverType.TURN_LEFT
        Instruction.TURN_RIGHT -> ManeuverType.TURN_RIGHT
        Instruction.TURN_SLIGHT_LEFT -> ManeuverType.TURN_SLIGHT_LEFT
        Instruction.TURN_SLIGHT_RIGHT -> ManeuverType.TURN_SLIGHT_RIGHT
        Instruction.TURN_SHARP_LEFT -> ManeuverType.TURN_SHARP_LEFT
        Instruction.TURN_SHARP_RIGHT -> ManeuverType.TURN_SHARP_RIGHT
        Instruction.CONTINUE_ON_STREET -> ManeuverType.STRAIGHT
        Instruction.USE_ROUNDABOUT -> ManeuverType.ROUNDABOUT
        Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN -> ManeuverType.U_TURN
        Instruction.FINISH -> ManeuverType.ARRIVE
        Instruction.REACHED_VIA -> ManeuverType.WAYPOINT
        Instruction.KEEP_LEFT -> ManeuverType.KEEP_LEFT
        Instruction.KEEP_RIGHT -> ManeuverType.KEEP_RIGHT
        else -> ManeuverType.UNKNOWN
    }

    private fun RoutingProfile.toGraphHopperProfile(): String = when (this) {
        RoutingProfile.BEZPIECZNY -> "enduro_normal"
        RoutingProfile.TERENOWY -> "enduro_max"
        RoutingProfile.ODKRYWCZY -> "enduro_extreme"
    }

    private fun calculatePointAtBearing(
        origin: GeoPoint,
        bearingDegrees: Double,
        distanceMeters: Double
    ): GeoPoint {
        val R = 6_371_000.0
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)
        val bearing = Math.toRadians(bearingDegrees)
        val d = distanceMeters / R

        val lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(d) +
                    Math.cos(lat1) * Math.sin(d) * Math.cos(bearing)
        )
        val lon2 = lon1 + Math.atan2(
            Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
            Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
        )

        return GeoPoint(
            latitude = Math.toDegrees(lat2),
            longitude = Math.toDegrees(lon2)
        )
    }
}
