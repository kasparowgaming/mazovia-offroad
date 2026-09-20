package pl.mazovia.offroad.routing.benchmark

import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.LoopParameters
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.routing.RoutingResult
import pl.mazovia.offroad.routing.engine.GraphHopperRoutingEngine
import java.io.File
import kotlin.system.measureNanoTime

class RouteBenchmarkRunnerTest {

    private lateinit var engine: GraphHopperRoutingEngine

    @Before
    fun setup() {
        // 2. PROVE PURE-JVM EXECUTION USES PRODUCTION ROUTING
        // Mock android.util.Log so the pure-JVM test can run without Android
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        
        engine = GraphHopperRoutingEngine()
    }

    @Test
    fun runFullBenchmarkSuite() = runBlocking {
        // 1. BENCHMARK MUST BE OPT-IN
        val isOptIn = System.getenv("MAZOVIA_RUN_ROUTE_BENCHMARK") == "1"
        org.junit.Assume.assumeTrue("Benchmark is not enabled. Set MAZOVIA_RUN_ROUTE_BENCHMARK=1 to run.", isOptIn)
        
        println("==================================================")
        println("MAZOVIA OFFROAD ROUTE BENCHMARK")
        println("==================================================")
        println("NOTE: This pure-JVM benchmark is authoritative for route quality, deterministic comparison,")
        println("candidate behavior, scoring, failures, and relative JVM algorithm latency.")
        println("It is NOT authoritative for Android-device latency, Android heap usage, battery,")
        println("ART GC behavior, or UI responsiveness.")
        println("==================================================\n")
        
        // 1. GRAPH DATA MUST REPRESENT THE CURRENT APP
        val envPath = System.getenv("MAZOVIA_GRAPH_PATH")
        val fallbackPath = File(System.getProperty("user.home"), "Desktop/MazoviaOffroad3/graph-cache-enduro-maz-lub").absolutePath
        val graphPath = envPath ?: fallbackPath

        val graphDir = File(graphPath)
        if (!graphDir.exists() || !graphDir.isDirectory) {
            fail("FAIL FAST: Graph cache directory does not exist at $graphPath. Please set MAZOVIA_GRAPH_PATH environment variable.")
        }

        println("Loading graph from: $graphPath")
        val isLoaded = engine.loadGraph(graphPath)
        assertTrue("FAIL FAST: Failed to load graph. Graph data may be incompatible.", isLoaded)

        val state = engine.getState()
        println("Graph State: version=${state.graphVersion}, path=${state.graphPath}")
        println("Available Profiles: ${state.supportedProfiles.joinToString()}")
        
        // 6. SCENARIOS (Ported from legacy EnduroProfileBenchmark.java)
        val scenarios = listOf(
            BenchmarkScenario("Stok-Lacki-Biardy", "point-to-point", "", 0, 0.0,
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.0240800, 22.3118500))),
            BenchmarkScenario("Stok-Lacki-Lokalna", "point-to-point", "", 0, 0.0,
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.1192, 22.3950))),
            BenchmarkScenario("Stok-Lacki-Las-Lipiny", "forest", "", 0, 0.0,
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.1218, 22.4435))),
            BenchmarkScenario("Stok-Lacki-Las-Zabokliki", "forest", "", 0, 0.0,
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.1888, 22.2490))),
            BenchmarkScenario("Stok-Lacki-Las-Holubla", "forest", "", 0, 0.0,
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.2760, 22.4110))),
            
            // Loop intent - Legacy used fixed geometry to force 3 variants. 
            // We store the full geometry to prevent silent data loss, but currently only execute the start point + targetKm 
            // via the modern `generateLoopCandidates` as per instructions. This semantic mismatch will be reported.
            BenchmarkScenario("Petla-20-A", "loop", "loop-20", 1, 20.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.1737, 22.3659), GeoPoint(52.1499, 22.3904), GeoPoint(52.1334, 22.3519), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-20-B", "loop", "loop-20", 2, 20.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.1754, 22.3344), GeoPoint(52.1597, 22.3064), GeoPoint(52.1352, 22.3327), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-20-C", "loop", "loop-20", 3, 20.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.1614, 22.3782), GeoPoint(52.1369, 22.3747), GeoPoint(52.1317, 22.3414), GeoPoint(52.1567802, 22.3448868))),
            
            BenchmarkScenario("Petla-50-A", "loop", "loop-50", 1, 50.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.2089, 22.3969), GeoPoint(52.1461, 22.4629), GeoPoint(52.0923, 22.3489), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-50-B", "loop", "loop-50", 2, 50.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.2281, 22.3109), GeoPoint(52.1861, 22.2209), GeoPoint(52.0981, 22.2889), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-50-C", "loop", "loop-50", 3, 50.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.1981, 22.4469), GeoPoint(52.1061, 22.4829), GeoPoint(52.0701, 22.3629), GeoPoint(52.1567802, 22.3448868))),
            
            BenchmarkScenario("Petla-100-A", "loop", "loop-100", 1, 100.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.2940, 22.4480), GeoPoint(52.1510, 22.5900), GeoPoint(52.0180, 22.3900), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-100-B", "loop", "loop-100", 2, 100.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.3100, 22.2700), GeoPoint(52.2100, 22.0800), GeoPoint(52.0150, 22.1900), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-100-C", "loop", "loop-100", 3, 100.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.2450, 22.5650), GeoPoint(52.0500, 22.6200), GeoPoint(51.9950, 22.3600), GeoPoint(52.1567802, 22.3448868))),
            
            BenchmarkScenario("Petla-150-A", "loop", "loop-150", 1, 150.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.3700, 22.5050), GeoPoint(52.1450, 22.7350), GeoPoint(51.9400, 22.4200), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-150-B", "loop", "loop-150", 2, 150.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.3950, 22.2250), GeoPoint(52.2350, 21.9300), GeoPoint(51.9200, 22.1050), GeoPoint(52.1567802, 22.3448868))),
            BenchmarkScenario("Petla-150-C", "loop", "loop-150", 3, 150.0, 
                listOf(GeoPoint(52.1567802, 22.3448868), GeoPoint(52.3000, 22.6900), GeoPoint(51.9900, 22.7900), GeoPoint(51.8900, 22.3700), GeoPoint(52.1567802, 22.3448868)))
        )
        
        // Exact comma-separated names; empty/unknown filters fail instead of silently running everything.
        val scenarioFilter = System.getenv("MAZOVIA_BENCH_SCENARIOS")?.split(",")?.map { it.trim() }?.toSet()
        val profileFilter = System.getenv("MAZOVIA_BENCH_PROFILES")?.split(",")?.map { it.trim() }?.toSet()
        require(scenarioFilter == null || (scenarioFilter.isNotEmpty() && scenarioFilter.all { name -> scenarios.any { it.name == name } }))
        require(profileFilter == null || (profileFilter.isNotEmpty() && profileFilter.all { name -> RoutingProfile.entries.any { it.name == name } }))
        val selectedScenarios = scenarios.filter { scenarioFilter == null || it.name in scenarioFilter }
        val profiles = RoutingProfile.entries.filter { profileFilter == null || it.name in profileFilter }
        println("Executing ${selectedScenarios.size} scenarios x ${profiles.size} profiles; 1 warmup + 2 measured runs")
        println("Loop A/B/C names are legacy labels ONLY: current loops use start + targetKm, not legacy waypoint geometry.")
        val iterations = 3 // 1 warmup + 2 measured
        
        val summaryResults = mutableListOf<BenchmarkSummaryResult>()
        val candidateResults = mutableListOf<BenchmarkCandidateResult>()
        
        for (scenario in selectedScenarios) {
            for (profile in profiles) {
                for (runIndex in 0 until iterations) {
                    val isWarmup = runIndex == 0
                    
                    val listener = RecordingBenchmarkListener(scenario.name, profile, runIndex, isWarmup, scenario.kind == "loop")
                    engine.benchmarkListener = listener
                    
                    val usedHeapBefore = measureUsedHeap()
                    
                    var success = false
                    var failureReason: String? = null
                    var route: Route? = null
                    
                    val totalTimeNano = measureNanoTime {
                        if (scenario.kind == "loop") {
                            // 6. LOOP SCENARIOS
                            try {
                                val loopCandidates = engine.generateLoopCandidates(
                                    LoopParameters(startPoint = scenario.origin, targetDistanceKm = scenario.targetKm.toInt(), profile = profile)
                                )
                                if (loopCandidates.isNotEmpty()) {
                                    success = true
                                    route = loopCandidates.first().route
                                } else {
                                    failureReason = "No loops generated"
                                }
                            } catch (e: Exception) {
                                failureReason = e.message
                            }
                        } else {
                            // POINT-TO-POINT
                            val result = engine.calculateRoute(scenario.origin, scenario.destination, profile)
                            when (result) {
                                is RoutingResult.Success -> {
                                    success = true
                                    route = result.route
                                }
                                is RoutingResult.Error -> {
                                    failureReason = result.error.name
                                }
                            }
                        }
                    }
                    
                    listener.completeScenario(route)
                    val usedHeapAfter = measureUsedHeap()
                    

                    
                    // Summarize
                    if (!isWarmup) {
                        val summary = listener.summarize(scenario, route, failureReason, totalTimeNano, usedHeapBefore, usedHeapAfter)
                        summaryResults.add(summary)
                        candidateResults.addAll(listener.capturedCandidates)
                        
                        println("Ran ${scenario.name} | ${profile.name} | Success: $success | Time: ${summary.totalTimeMs}ms")
                    }
                }
            }
        }
        
        // Write outputs
        val outDir = File("build/benchmark-reports")
        outDir.mkdirs()
        BenchmarkCsvExporter.writeSummaryCsv(File(outDir, "benchmark-summary.csv"), summaryResults)
        BenchmarkCsvExporter.writeCandidateCsv(File(outDir, "benchmark-candidates.csv"), candidateResults)
        
        engine.benchmarkListener = null
        engine.unloadGraph()
        println("Benchmark completed. Results written to ${outDir.absolutePath}")
    }

    private fun measureUsedHeap(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }
}
