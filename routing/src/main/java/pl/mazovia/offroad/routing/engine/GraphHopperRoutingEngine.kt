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
import kotlinx.coroutines.ensureActive


import pl.mazovia.offroad.domain.model.*
import pl.mazovia.offroad.domain.routing.*
import pl.mazovia.offroad.routing.profile.OffroadProfileConfig
import java.io.File
import java.util.UUID

/**
 * Real GraphHopper routing engine implementation.
 * Targets offline local routing with memory-efficient graph access.
 */
open class GraphHopperRoutingEngine : RoutingEngine {

    internal interface Filesystem {
        fun exists(file: File): Boolean
        fun renameTo(src: File, dest: File): Boolean
        fun deleteRecursively(file: File): Boolean
    }

    internal var fs: Filesystem = object : Filesystem {
        override fun exists(file: File) = file.exists()
        override fun renameTo(src: File, dest: File) = src.renameTo(dest)
        override fun deleteRecursively(file: File) = file.deleteRecursively()
    }

    private data class EngineState(
        val graphHopper: GraphHopper? = null,
        val currentGraphPath: String? = null,
        val lastError: String? = null
    )

    @Volatile
    private var engineState = EngineState()

    // Safety mutex to ensure graph swapping/unloading does not race with active routing
    private val engineMutex = Mutex()

    // Visible for testing / benchmarking
    internal var benchmarkListener: BenchmarkListener? = null

    internal interface BenchmarkListener {
        fun onRouteStarted() {}
        fun onCandidateEvaluated(candidateId: String, route: Route?, isBaseline: Boolean, waypoints: List<GeoPoint>, elapsedMs: Long, error: String?, routingError: RoutingError?) {}
        fun onTournamentFinished(winner: Route?, evaluations: List<RouteTournament.CandidateEvaluation>, elapsedNanos: Long) {}
        fun onRouteFinished(selected: Route?, tournamentStatus: String) {}
        fun onLoopAttempt(candidateId: String, geometry: String, targetKm: Int, candidate: LoopCandidate?, status: String, selected: Boolean) {}
    }

    private inline fun observe(listener: BenchmarkListener?, event: (BenchmarkListener) -> Unit) {
        try {
            if (listener != null) event(listener)
        } catch (e: Exception) {
            android.util.Log.e("GraphHopperDiagnostic", "BenchmarkListener failed", e)
        }
    }




    override suspend fun isReady(): Boolean = engineMutex.withLock {
        engineState.graphHopper != null
    }

    override suspend fun getState(): RoutingEngineState = engineMutex.withLock {
        val stateSnapshot = engineState
        val gh = stateSnapshot.graphHopper
        if (gh != null) {
            RoutingEngineState(
                isGraphLoaded = true,
                graphPath = stateSnapshot.currentGraphPath,
                graphVersion = "GH-9.1",
                nodeCount = gh.baseGraph.nodes.toLong(),
                edgeCount = gh.baseGraph.edges.toLong(),
                memoryUsageBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                supportedProfiles = RoutingProfile.entries.toList(),
                lastError = stateSnapshot.lastError
            )
        } else {
            RoutingEngineState(
                isGraphLoaded = false,
                graphPath = stateSnapshot.currentGraphPath,
                lastError = stateSnapshot.lastError
            )
        }
    }

    internal open fun initGraphHopper(graphPath: String): GraphHopper {
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
                val newHopper = initGraphHopper(graphPath)
                val oldState = engineState
                engineState = EngineState(
                    graphHopper = newHopper,
                    currentGraphPath = graphPath,
                    lastError = null
                )
                unloadGraphInternal(oldState)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                engineState = engineState.copy(lastError = "Failed to load graph: ${e.message}")
                false
            }
        }
    }

    override suspend fun validateAndSwapGraph(tempGraphPath: String): RoutingEngine.ImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(tempGraphPath)
        try {
            val tempHopper = initGraphHopper(tempGraphPath)
            tempHopper.close()

            engineMutex.withLock {
                val activeDir = File(tempDir.parentFile, "graph")
                val backupDir = File(tempDir.parentFile, "graph_backup")

                if (fs.exists(backupDir)) {
                    return@withLock RoutingEngine.ImportResult.Error(
                        "Refusing graph swap: graph_backup already exists"
                    )
                }

                val oldState = engineState
                val hadActive = fs.exists(activeDir)
                var backedUp = false
                var installed = false
                var newHopper: GraphHopper? = null
                try {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (hadActive) {
                        check(fs.renameTo(activeDir, backupDir)) { "Failed to backup existing graph" }
                        backedUp = true
                    }
                    check(fs.renameTo(tempDir, activeDir)) { "Failed to move new graph to active directory" }
                    installed = true
                    kotlin.coroutines.coroutineContext.ensureActive()
                    newHopper = initGraphHopper(activeDir.absolutePath)
                    // Synchronous initialization may return normally after cancellation.
                    // This is the last cancellation boundary before committing the swap.
                    kotlin.coroutines.coroutineContext.ensureActive()
                } catch (failure: Throwable) {
                    val rollbackFailure = withContext(kotlinx.coroutines.NonCancellable) {
                        try {
                            newHopper?.close()
                        } catch (closeFailure: Throwable) {
                            failure.addSuppressed(closeFailure)
                        }
                        try {
                            rollbackGraphSwap(activeDir, backupDir, backedUp, installed)
                        } catch (rollbackError: Throwable) {
                            IllegalStateException("CRITICAL: graph rollback failed", rollbackError)
                        }
                    }
                    engineState = oldState.copy(
                        // The old RAM graph remains usable, but its disk location is
                        // no longer guaranteed after an unsuccessful rollback.
                        currentGraphPath = if (rollbackFailure == null) oldState.currentGraphPath else null,
                        lastError = rollbackFailure?.message ?: "Graph swap failed: ${failure.message}"
                    )
                    if (rollbackFailure != null) {
                        failure.addSuppressed(rollbackFailure)
                    }
                    if (failure is CancellationException) throw failure
                    if (failure !is Exception) throw failure
                    return@withLock RoutingEngine.ImportResult.Error(engineState.lastError!!)
                }

                engineState = EngineState(
                    graphHopper = newHopper,
                    currentGraphPath = activeDir.absolutePath,
                    lastError = null
                )
                unloadGraphInternal(oldState)

                if (hadActive && !fs.deleteRecursively(backupDir)) {
                    engineState = engineState.copy(
                        lastError = "CRITICAL: New graph is active but old backup cleanup failed"
                    )
                    return@withLock RoutingEngine.ImportResult.Error(engineState.lastError!!)
                }
                RoutingEngine.ImportResult.Success
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                if (fs.exists(tempDir)) fs.deleteRecursively(tempDir)
            }
            throw e
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.NonCancellable) {
                if (fs.exists(tempDir)) fs.deleteRecursively(tempDir)
            }
            RoutingEngine.ImportResult.Error(e.message ?: "Nieznany błąd podczas weryfikacji grafu.")
        }
    }

    private fun rollbackGraphSwap(activeDir: File, backupDir: File, backedUp: Boolean, installed: Boolean): Throwable? {
        if (installed && fs.exists(activeDir) && !fs.deleteRecursively(activeDir)) {
            return IllegalStateException("CRITICAL: failed to remove replacement graph during rollback")
        }
        if (backedUp) {
            if (!fs.exists(backupDir)) {
                return IllegalStateException("CRITICAL: graph backup is missing during rollback")
            }
            if (!fs.renameTo(backupDir, activeDir)) {
                return IllegalStateException(
                    "CRITICAL: graph load failed and backup restore failed; backup remains in graph_backup"
                )
            }
        }
        return null
    }

    override suspend fun unloadGraph() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val oldState = engineState
            engineState = EngineState()
            unloadGraphInternal(oldState)
        }
    }

    private fun unloadGraphInternal(oldState: EngineState) {
        try {
            oldState.graphHopper?.close()
        } catch (_: Exception) {}
    }

    override suspend fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        waypoints: List<GeoPoint>
    ): RoutingResult = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val gh = engineState.graphHopper ?: return@withLock RoutingResult.Error(RoutingError.GRAPH_NOT_LOADED)
            val listener = benchmarkListener
            observe(listener) { it.onRouteStarted() }

            kotlin.coroutines.coroutineContext.ensureActive()
            // Base route calculation (not speculative)
            val baselineResult = calculateSingleRoute(gh, origin, destination, profile, waypoints, isSpeculative = false, candidateId = "baseline", listener = listener)
            kotlin.coroutines.coroutineContext.ensureActive()

            // If there are specific waypoints or if profile is BEZPIECZNY, just return the baseline
            if (waypoints.isNotEmpty() || profile == RoutingProfile.BEZPIECZNY) {
                observe(listener) { it.onRouteFinished((baselineResult as? RoutingResult.Success)?.route, "NOT_APPLICABLE") }
                return@withLock baselineResult
            }

            // We only proceed with geometric alternatives if baseline succeeds
            val baselineRoute = (baselineResult as? RoutingResult.Success)?.route ?: run {
                observe(listener) { it.onRouteFinished(null, "BASELINE_FAILED") }
                return@withLock baselineResult
            }

            try {
                val corridors = RouteTournament.profileDiversityCorridors(origin, destination, profile)
                if (corridors.isEmpty()) {
                    observe(listener) { it.onRouteFinished(baselineRoute, "NO_CORRIDORS") }
                    return@withLock baselineResult
                }

                android.util.Log.e("GraphHopperDiagnostic", "Generating ${corridors.size} alternative corridors sequentially")

                val candidates = mutableListOf<Route>()
                for ((index, corridor) in corridors.withIndex()) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    try {
                        val result = calculateSingleRoute(gh, origin, destination, profile, corridor, isSpeculative = true, candidateId = "corridor-${index + 1}", listener = listener)
                        kotlin.coroutines.coroutineContext.ensureActive()
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
                val evaluations = if (listener != null) mutableListOf<RouteTournament.CandidateEvaluation>() else null
                val tournamentStart = System.nanoTime()
                val winner = RouteTournament.chooseTournamentWinner(candidates, baselineRoute.totalDistanceMeters, profile, evaluations)
                // Evaluation/selection only: excludes GH routing and listener/CSV work.
                val tournamentNanos = System.nanoTime() - tournamentStart
                observe(listener) { it.onTournamentFinished(winner, evaluations.orEmpty(), tournamentNanos) }
                observe(listener) { it.onRouteFinished(winner ?: baselineRoute, if (winner != null) "EVALUATED" else "FALLBACK") }

                if (winner != null) {
                    return@withLock RoutingResult.Success(winner)
                }
                return@withLock baselineResult
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                observe(listener) { it.onRouteFinished(baselineRoute, "EVALUATION_FAILED") }
                android.util.Log.e("GraphHopperDiagnostic", "Tournament failed, falling back to baseline", e)
                return@withLock baselineResult
            }
        }
    }

    private suspend fun calculateSingleRoute(
        gh: GraphHopper,
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        waypoints: List<GeoPoint>,
        isSpeculative: Boolean = false,
        candidateId: String,
        listener: BenchmarkListener?
    ): RoutingResult {
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

            val response = gh.route(request)

            android.util.Log.e("GraphHopperDiagnostic", "HOPPER_ROUTE_RETURNED")

            kotlin.coroutines.coroutineContext.ensureActive() // ensure active immediately after gh returns

            if (response.hasErrors()) {
                val err = response.errors.joinToString { it.message ?: "Unknown error" }
                errorMsg = err
                android.util.Log.e("GraphHopperDiagnostic", "ROUTE ERROR: " + err)
                if (!isSpeculative) {
                    engineState = engineState.copy(lastError = err)
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
            if (!isSpeculative) engineState = engineState.copy(lastError = errorMsg)
            RoutingResult.Error(RoutingError.MEMORY_ERROR)
        } catch (e: Exception) {
            android.util.Log.e("GraphHopperDiagnostic", "FATAL EXCEPTION in calculateSingleRoute", e)
            errorMsg = "Exception: ${e.message}"
            if (!isSpeculative) engineState = engineState.copy(lastError = "Routing error: " + e.message)
            RoutingResult.Error(RoutingError.CALCULATION_ERROR)
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        observe(listener) { it.onCandidateEvaluated(candidateId, resultRoute, !isSpeculative, waypoints, elapsedMs, errorMsg, (result as? RoutingResult.Error)?.error) }

        return result
    }

    override suspend fun calculateAlternatives(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        maxAlternatives: Int
    ): List<RoutingResult> = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val gh = engineState.graphHopper ?: return@withLock listOf(RoutingResult.Error(RoutingError.GRAPH_NOT_LOADED))

            kotlin.coroutines.coroutineContext.ensureActive()

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

                val response = gh.route(request)
                kotlin.coroutines.coroutineContext.ensureActive()

                if (response.hasErrors()) {
                    val errorMsg = response.errors.firstOrNull()?.message ?: "Unknown error"
                    engineState = engineState.copy(lastError = errorMsg)
                    return@withLock listOf(RoutingResult.Error(RoutingError.NO_ROUTE_FOUND))
                }

                response.all.map { path ->
                    val route = convertToRoute(path, origin, destination, profile)
                    RoutingResult.Success(route)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                engineState = engineState.copy(lastError = "Alternatives error: ${e.message}")
                listOf(RoutingResult.Error(RoutingError.CALCULATION_ERROR))
            }
        }
    }

    override suspend fun generateLoopCandidates(
        params: LoopParameters,
        candidateCount: Int
    ): List<LoopCandidate> = withContext(Dispatchers.IO) {
        if (engineState.graphHopper == null || params.targetDistanceKm <= 0 || candidateCount <= 0) return@withContext emptyList()
        suspend fun attempt(shapes: List<LoopPlanner.Shape>) = shapes.map { shape ->
                kotlin.coroutines.coroutineContext.ensureActive()
                try {
                    when (val result = calculateRoute(params.startPoint, params.startPoint,
                        params.profile, shape.waypoints)) {
                        is RoutingResult.Success -> LoopPlanner.Attempt(shape, result.route)
                        is RoutingResult.Error -> LoopPlanner.Attempt(shape, null, result.error.name)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LoopPlanner.Attempt(shape, null, "ROUTING_FAILURE: ${e.message}")
                }
            }
        val attempts = attempt(LoopPlanner.shapes(params.startPoint, params.targetDistanceKm, params.preferredDirection))
        var decisions = LoopPlanner.select(attempts, params.targetDistanceKm, candidateCount)
        // Only recover when the original bounded search has no acceptable geometry. In
        // particular, an existing long-loop fallback keeps its original route and distance.
        if (decisions.none { it.status == "SELECTED" || it.status == "SELECTED_FALLBACK" }) {
            val recovery = attempt(LoopPlanner.shapes(params.startPoint, params.targetDistanceKm,
                params.preferredDirection, recovery = true))
            decisions = LoopPlanner.select(attempts + recovery, params.targetDistanceKm, candidateCount)
        }
        decisions.forEach { decision ->
            val selected = decision.status == "SELECTED" || decision.status == "SELECTED_FALLBACK"
            observe(benchmarkListener) { it.onLoopAttempt(decision.shape.id, decision.shape.description,
                params.targetDistanceKm, decision.candidate, decision.status, selected) }
            android.util.Log.e("LoopDiagnostic", "id=${decision.shape.id} geometry=${decision.shape.description} " +
                "targetKm=${params.targetDistanceKm} actualMeters=${decision.candidate?.route?.totalDistanceMeters} " +
                "error=${decision.candidate?.score?.targetDistanceError} terrainMeters=${decision.candidate?.route?.metrics?.offRoadDistanceMeters} " +
                "terrainPercent=${decision.candidate?.route?.metrics?.offRoadPercentage} longestTerrainMeters=${decision.candidate?.route?.metrics?.longestContinuousTerrainMeters} " +
                "retraceMeters=${decision.candidate?.retraceDistanceMeters} retraceRatio=${decision.candidate?.score?.retraceFraction} " +
                "localSpikeDistanceMeters=${decision.candidate?.localSpikeDistanceMeters} localSpikeRatio=${decision.candidate?.localSpikeRatio} " +
                "spikeRejected=${decision.candidate?.spikeRejected} spikeWaypointIndex=${decision.candidate?.spikeWaypointIndex} " +
                "status=${decision.status} selected=$selected")
        }
        val selectedIds = decisions.filter { it.status == "SELECTED" || it.status == "SELECTED_FALLBACK" }
            .map { it.shape.id }.toSet()
        // select() returns diagnostics in generation order; selection order is reconstructed
        // from the same deterministic policy for the API's first recommended candidate.
        decisions.filter { it.shape.id in selectedIds }.mapNotNull { it.candidate }
            .sortedWith(compareBy<LoopCandidate> {
                if (it.status == "FALLBACK_25_PERCENT") it.score.targetDistanceError
                else if (it.score.retraceFraction <= 0.10) 0.0 else 1.0
            }.thenBy {
                if (it.status == "FALLBACK_25_PERCENT") { if (it.score.retraceFraction <= 0.10) 0.0 else 1.0 }
                else kotlin.math.floor(it.score.targetDistanceError / 0.05)
            }
                .thenByDescending { it.route.metrics.offRoadDistanceMeters - it.retraceDistanceMeters }
                .thenByDescending { it.route.metrics.longestContinuousTerrainMeters }
                .thenBy { it.route.metrics.longestAsphaltConnectorMeters }
                .thenBy { it.score.targetDistanceError }
                .thenBy { it.candidateId })
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

    internal fun extractSegments(path: ResponsePath, allPoints: List<GeoPoint>): List<RouteSegment> {
        if (allPoints.size < 2) return emptyList()

        val segments = mutableListOf<RouteSegment>()
        val pathDetails = path.pathDetails

        val isDiagnostic = path.distance > 0 && path.distance < 20000 && allPoints.size > 10 // roughly matching the diagnostic route
        var shouldDiagnose = false

        val surfaceDetails = pathDetails["surface"]
        val roadClassDetails = pathDetails["road_class"]
        val trackTypeDetails = pathDetails["track_type"]

        if (System.getProperty("MAZOVIA_DIAGNOSE_GH") == "true") {
            shouldDiagnose = true
            println("=== GH PATH DETAILS DUMP ===")
            println("- contains surface: ${surfaceDetails != null}")
            println("- contains road_class: ${roadClassDetails != null}")
            println("- contains track_type: ${trackTypeDetails != null}")
        }

        // Collect all unique boundaries
        val boundaries = java.util.TreeSet<Int>()
        surfaceDetails?.forEach { boundaries.add(it.first as Int); boundaries.add(it.last as Int) }
        roadClassDetails?.forEach { boundaries.add(it.first as Int); boundaries.add(it.last as Int) }
        trackTypeDetails?.forEach { boundaries.add(it.first as Int); boundaries.add(it.last as Int) }
        boundaries.add(0)
        boundaries.add(allPoints.size - 1)

        val sortedBoundaries = boundaries.toList()

        for (i in 0 until sortedBoundaries.size - 1) {
            val fromIdx = sortedBoundaries[i].coerceIn(0, allPoints.size - 1)
            val toIdx = sortedBoundaries[i + 1].coerceIn(1, allPoints.size - 1)

            if (fromIdx >= toIdx) continue

            val segPoints = allPoints.subList(fromIdx, toIdx + 1)

            if (segPoints.size >= 2) {
                val distance = segPoints.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

                val rawSurface = surfaceDetails?.find { (it.first as Int) <= fromIdx && fromIdx < (it.last as Int) }?.value?.toString()
                val rawRc = roadClassDetails?.find { (it.first as Int) <= fromIdx && fromIdx < (it.last as Int) }?.value?.toString()
                val rawTt = trackTypeDetails?.find { (it.first as Int) <= fromIdx && fromIdx < (it.last as Int) }?.value?.toString()

                val surfaceTag = if (rawSurface.equals("missing", ignoreCase = true)) null else rawSurface
                val surface = Surface.fromOsmTag(surfaceTag)

                val rcTag = if (rawRc.equals("missing", ignoreCase = true)) null else rawRc
                val highway = HighwayType.fromOsmTag(rcTag)

                val ttTag = if (rawTt.equals("missing", ignoreCase = true)) null else rawTt
                val trackType = TrackType.fromOsmTag(ttTag)

                segments.add(
                    RouteSegment(
                        points = segPoints,
                        distanceMeters = distance,
                        surface = surface,
                        highway = highway,
                        trackType = trackType,
                        hasSurfaceOrRoadClassDetail = surfaceTag != null || rcTag != null,
                        roadDataConfidence = RoadDataConfidenceResolver.resolve(
                            surface = surface,
                            trackType = trackType,
                            highway = highway,
                            existenceSources = setOf(RoadDataSource.ROUTING_GRAPH),
                            surfaceSource = RoadDataSource.ROUTING_GRAPH
                        )
                    )
                )
            }
        }

        if (segments.isEmpty()) {
            val distance = allPoints.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
            segments.add(
                RouteSegment(
                    points = allPoints,
                    distanceMeters = distance,
                    surface = Surface.UNKNOWN,
                    highway = HighwayType.UNKNOWN,
                    trackType = TrackType.UNKNOWN,
                    roadDataConfidence = RoadDataConfidenceResolver.resolve(
                        existenceSources = setOf(RoadDataSource.ROUTING_GRAPH),
                        surfaceSource = RoadDataSource.ROUTING_GRAPH
                    )
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
