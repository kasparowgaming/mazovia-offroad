import re

with open(r'routing\src\main\java\pl\mazovia\offroad\routing\engine\GraphHopperRoutingEngine.kt', 'r', encoding='utf-8') as f:
    content = f.read()

fixed = re.sub(r'override suspend fun calculateRoute\([\s\S]*?fun calculateAlternatives\(', '''override suspend fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RoutingProfile,
        waypoints: List<GeoPoint>
    ): RoutingResult = withContext(Dispatchers.IO) {
        val gh = graphHopper ?: return@withContext RoutingResult.Error(RoutingError.GRAPH_NOT_LOADED)

        try {
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
                putHint(Parameters.Routing.INSTRUCTIONS, true)
                putHint(Parameters.Details.PATH_DETAILS, listOf("surface", "road_class", "track_type"))
                putHint("ch.disable", true)
            }
            android.util.Log.e("GraphHopperDiagnostic", "GHREQUEST_CREATED")

            android.util.Log.e("GraphHopperDiagnostic", "HOPPER_ROUTE_START")
            val response = gh.route(request)
            android.util.Log.e("GraphHopperDiagnostic", "HOPPER_ROUTE_RETURNED")

            if (response.hasErrors()) {
                val errorMsg = response.errors.joinToString { it.message ?: "Unknown error" }
                android.util.Log.e("GraphHopperDiagnostic", "ROUTE ERROR: " + errorMsg)
                lastError = errorMsg
                return@withContext when {
                    errorMsg.contains("Cannot find point", ignoreCase = true) ->
                        RoutingResult.Error(RoutingError.POINT_NOT_FOUND)
                    errorMsg.contains("Connection between locations not found", ignoreCase = true) ->
                        RoutingResult.Error(RoutingError.NO_ROUTE_FOUND)
                    else -> RoutingResult.Error(RoutingError.CALCULATION_ERROR)
                }
            }

            android.util.Log.e("GraphHopperDiagnostic", "RESPONSE_PATH_COUNT: " + response.all.size)
            val best = response.best
            
            android.util.Log.e("GraphHopperDiagnostic", "ROUTE_EXTRACTION_START")
            val route = convertToRoute(best, origin, destination, profile)
            android.util.Log.e("GraphHopperDiagnostic", "ROUTE_EXTRACTION_DONE")
            
            android.util.Log.e("GraphHopperDiagnostic", "ROUTE_METRICS_START")
            android.util.Log.e("GraphHopperDiagnostic", "ROUTE_METRICS_DONE")

            RoutingResult.Success(route)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("GraphHopperDiagnostic", "OOM in calculateRoute", e)
            lastError = "OOM: " + e.message
            RoutingResult.Error(RoutingError.MEMORY_ERROR)
        } catch (e: Exception) {
            android.util.Log.e("GraphHopperDiagnostic", "FATAL EXCEPTION in calculateRoute", e)
            lastError = "Routing error: " + e.message
            RoutingResult.Error(RoutingError.CALCULATION_ERROR)
        }
    }

    override suspend fun calculateAlternatives(''' , content)

with open(r'routing\src\main\java\pl\mazovia\offroad\routing\engine\GraphHopperRoutingEngine.kt', 'w', encoding='utf-8') as f:
    f.write(fixed)
