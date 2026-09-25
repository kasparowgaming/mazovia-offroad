package pl.mazovia.offroad.terrain.validation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.mazovia.offroad.domain.model.GeoPoint
import pl.mazovia.offroad.domain.model.HighwayType
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.RouteMetrics
import pl.mazovia.offroad.domain.model.RouteSegment
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.model.RoutingProfile
import pl.mazovia.offroad.domain.model.Surface
import pl.mazovia.offroad.terrain.dem.DemBlock
import pl.mazovia.offroad.terrain.dem.DemTileCache
import pl.mazovia.offroad.terrain.dem.PmtilesElevationSampler
import pl.mazovia.offroad.terrain.dem.PmtilesReader
import pl.mazovia.offroad.terrain.dem.PurePng
import pl.mazovia.offroad.terrain.dem.TerrainRgbTileDecoder
import pl.mazovia.offroad.terrain.dem.TileId
import pl.mazovia.offroad.terrain.dem.WebMercator
import pl.mazovia.offroad.terrain.elevation.ElevationSample
import pl.mazovia.offroad.terrain.elevation.ElevationSampler
import pl.mazovia.offroad.terrain.elevation.ElevationSourceMetadata
import pl.mazovia.offroad.terrain.elevation.GeoBounds
import pl.mazovia.offroad.terrain.elevation.UnavailableReason
import pl.mazovia.offroad.terrain.profile.FilterConfig
import pl.mazovia.offroad.terrain.profile.FilteredElevationProfile
import pl.mazovia.offroad.terrain.profile.GradeEvent
import pl.mazovia.offroad.terrain.profile.GradeEventClass
import pl.mazovia.offroad.terrain.profile.GradeEventDetector
import pl.mazovia.offroad.terrain.profile.GradeEventType
import pl.mazovia.offroad.terrain.profile.GradeProfile
import pl.mazovia.offroad.terrain.profile.GradeStatus
import pl.mazovia.offroad.terrain.profile.ProfileAnomalyDetector
import pl.mazovia.offroad.terrain.profile.RawElevationProfile
import pl.mazovia.offroad.terrain.projection.RouteIndex
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil

/**
 * TA-001B G-DATA validation harness (JVM). Opt-in: `-Pterrain.gdata.workDir=<work dir>` (and optionally
 * `-Pterrain.gdata.outDir=<dir>` for the compact result JSON). Skipped otherwise.
 *
 * Phase A [exportProfilePositions]: 5 m profile positions of every frozen transect from the production RouteIndex /
 * RawElevationProfile.sample → `<work>/gdata/profile_positions.csv`. The Python reference stage then samples the 1 m NMT.
 * Phase B [evaluate]: reference and runtime (z15, z14 PMTiles through PmtilesElevationSampler) are fed through the SAME
 * production code — RawElevationProfile, ProfileAnomalyDetector, FilteredElevationProfile (FilterConfig.V1), GradeProfile,
 * GradeEventDetector, EventMatcher — per validation_routes.json `gate_interpretation`. No semantics are re-implemented.
 */
class GDataHarnessTest {
    private val work = System.getProperty("terrain.gdata.workDir")?.let(::File)
    private val outDir = System.getProperty("terrain.gdata.outDir")?.let(::File)
    private val declFile = File("../tools/terrain/validation/validation_routes.json")

    private data class Transect(val id: String, val vertices: List<GeoPoint>)

    private fun transects(): List<Transect> {
        val d = Json.parseToJsonElement(declFile.readText()).jsonObject
        return d["transects"]!!.jsonArray.map { t ->
            val o = t.jsonObject
            Transect(o["id"]!!.jsonPrimitive.content, o["vertices"]!!.jsonArray.map {
                val a = it.jsonArray
                GeoPoint(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double)
            })
        }
    }

    private fun route(t: Transect): Route {
        val seg = RouteSegment(
            points = t.vertices,
            distanceMeters = t.vertices.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) },
            surface = Surface.UNKNOWN,
            highway = HighwayType.UNKNOWN
        )
        return Route(t.id, t.vertices.first(), t.vertices.last(), listOf(seg), RouteMetrics.fromSegments(listOf(seg)),
            RoutingProfile.TERENOWY, source = RouteSource.CALCULATED_ROUTE)
    }

    /** Records positions only. */
    private class PositionProbe : ElevationSampler {
        override val metadata = ElevationSourceMetadata("probe", null, "none", "position probe")
        override fun sample(latitude: Double, longitude: Double) = ElevationSample.Unavailable(UnavailableReason.NOT_LOADED)
    }

    private fun positionsCsv(): String {
        val sb = StringBuilder("key,transect_id,lat,lon\n")
        for (t in transects()) {
            val raw = RawElevationProfile.sample(RouteIndex.build(route(t)), PositionProbe())
            for (i in 0 until raw.size) {
                val p = raw.positionAt(i)!!
                sb.append(t.id).append(':').append(i).append(',').append(t.id).append(',')
                    .append(p.latitude.toString()).append(',').append(p.longitude.toString()).append('\n')
            }
        }
        return sb.toString()
    }

    @Test
    fun exportProfilePositions() {
        assumeTrue("terrain.gdata.workDir not set", work != null)
        val dir = File(work, "gdata").apply { mkdirs() }
        File(dir, "profile_positions.csv").writeText(positionsCsv())
        println("GData phase A: wrote ${File(dir, "profile_positions.csv")}")
    }

    // ---------------------------------------------------------------- phase B

    private fun readCsv(f: File): List<List<String>> = f.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }

    /** Reference heights at exact positions (1 m NMT, sampled by the Python reference stage). */
    private class LookupSampler(private val map: Map<Pair<Long, Long>, Double>) : ElevationSampler {
        override val metadata = ElevationSourceMetadata("gugik-nmt-1m-reference", 1.0, "PL-EVRF2007-NH", "reference")
        override fun sample(latitude: Double, longitude: Double): ElevationSample {
            val h = map[latitude.toRawBits() to longitude.toRawBits()]
                ?: error("reference has no value for $latitude,$longitude (positions changed?)")
            return if (h.isNaN()) ElevationSample.Unavailable(UnavailableReason.NODATA) else ElevationSample.Value(h)
        }
    }

    private fun runtimeSampler(z: Int): PmtilesElevationSampler {
        val f = File(work, "output/validation/terrain_z$z.pmtiles")
        return PmtilesElevationSampler(PmtilesReader.open(f), TerrainRgbTileDecoder(PurePng.Decoder()), DemTileCache())
    }

    private fun prefetchFor(s: PmtilesElevationSampler, pts: List<GeoPoint>) = runBlocking {
        s.prefetch(GeoBounds.around(pts.map { it.latitude }.toDoubleArray(), pts.map { it.longitude }.toDoubleArray(), 30.0))
    }

    private fun rank(sorted: List<Double>, p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    private fun dist(values: List<Double>): JsonObject {
        if (values.isEmpty()) return buildJsonObject { put("n", 0) }
        val a = values.map { abs(it) }.sorted()
        return buildJsonObject {
            put("n", values.size)
            put("mean_abs", a.average()); put("p50", rank(a, 0.5)); put("p90", rank(a, 0.9)); put("p95", rank(a, 0.95))
            put("p99", rank(a, 0.99)); put("max", a.last()); put("bias_mean_signed", values.average())
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private class Profiles(val raw: RawElevationProfile, val grade: GradeProfile, val featureGrade: GradeProfile)

    private val featureRefConfig = FilterConfig(id = "FeatureRef-L25-raw", medianWindowSamples = 1, averageWindowSamples = 1, gradeWindowM = 25.0)

    private fun profiles(index: RouteIndex, sampler: ElevationSampler): Profiles {
        val raw = RawElevationProfile.sample(index, sampler)
        val anomalies = ProfileAnomalyDetector().detect(raw)
        val grade = GradeProfile.from(FilteredElevationProfile.from(raw, FilterConfig.V1), anomalies)
        val featureGrade = GradeProfile.from(FilteredElevationProfile.from(raw, featureRefConfig), anomalies)
        return Profiles(raw, grade, featureGrade)
    }

    private fun eventJson(e: GradeEvent) = buildJsonObject {
        put("type", e.type.name); put("class", e.eventClass.name); put("start_m", e.startDistanceM); put("end_m", e.endDistanceM)
        put("max_grade_pct", e.maxGrade * 100); put("elev_change_m", e.elevationChangeM)
    }

    @Test
    fun evaluate() {
        assumeTrue("terrain.gdata.workDir not set", work != null)
        val gd = File(work, "gdata")
        val posFile = File(gd, "profile_positions.csv")
        val posRef = File(gd, "profile_positions.reference.csv")
        assumeTrue("run phase A + reference stage first", posRef.exists())
        // determinism: phase-B positions must equal the exported ones bit for bit
        val regenerated = positionsCsv()
        assertEquals("profile positions changed since export", posFile.readText(), regenerated)

        val refByKey = readCsv(posRef).associate { it[0] to (it.getOrNull(1)?.takeIf { s -> s.isNotEmpty() }?.toDouble() ?: Double.NaN) }
        val lookup = HashMap<Pair<Long, Long>, Double>()
        for (r in readCsv(posFile)) lookup[r[2].toDouble().toRawBits() to r[3].toDouble().toRawBits()] = refByKey.getValue(r[0])
        val pointRows = readCsv(File(gd, "points.csv"))
        val pointRef = readCsv(File(gd, "points.reference.csv")).associate { it[0] to (it.getOrNull(1)?.takeIf { s -> s.isNotEmpty() }?.toDouble() ?: Double.NaN) }
        val refSampler = LookupSampler(lookup)
        val ts = transects()
        val detector = GradeEventDetector()
        val matcher = EventMatcher()
        val results = LinkedHashMap<String, JsonElement>()
        results["schema"] = JsonPrimitive("ta001b-gdata-results-1")
        results["validation_set_sha256"] = JsonPrimitive(sha256(declFile.readText()))
        results["profile_positions_sha256"] = JsonPrimitive(sha256(regenerated))
        results["profile_positions"] = JsonPrimitive(readCsv(posFile).size)

        val refProfiles = ts.associate { t -> t.id to profiles(RouteIndex.build(route(t)), refSampler) }

        // Diagnostic only (no verdict): G-DATA-4 decomposition — the same feature reference scored against the 1 m
        // REFERENCE DEM through FilterConfig-v1, i.e. the attenuation caused by the filter alone, without the z15/z14 DEM.
        run {
            val reports = ts.map { t ->
                val p = refProfiles.getValue(t.id)
                matcher.matchRoute(t.id, detector.detect(p.featureGrade), p.featureGrade, detector.detect(p.grade), p.grade)
            }
            val att = reports.flatMap { r -> r.truePositives.map { (abs(it.reference.maxGrade) - abs(it.runtime.maxGrade)) * 100.0 } }
            val tp = reports.sumOf { it.tp }; val fn = reports.sumOf { it.fn }
            val lost = reports.flatMap { r -> (r.falseNegatives + r.borderlineReferenceUnmatched).filter { it.eventClass == GradeEventClass.SHORT && abs(it.maxGrade) >= 0.12 - 1e-9 }.map { r.routeId to it } }
            results["gdata4_decomposition_reference_dem_filter_v1"] = buildJsonObject {
                put("note", "diagnostic only: feature reference vs 1 m reference DEM + FilterConfig-v1 (filter-only effect)")
                put("feature_tp", tp); put("feature_fn", fn); put("feature_recall", if (tp + fn == 0) null else tp.toDouble() / (tp + fn))
                put("median_peak_grade_attenuation_pp", if (att.isEmpty()) null else rank(att.sorted(), 0.5))
                put("peak_grade_attenuation_pp", dist(att))
                put("lost_severe_short_features", JsonArray(lost.map { (r, e) -> buildJsonObject { put("route", r); put("event", eventJson(e)) } }))
            }
        }

        for (z in listOf(15, 14)) {
            val zr = LinkedHashMap<String, JsonElement>()
            runtimeSampler(z).use { rt ->
                zr["build_id"] = JsonPrimitive(rt.archive.buildId)
                // ---------------- G-DATA-1
                val errors = ArrayList<Double>()
                val perTransect = LinkedHashMap<String, MutableList<Double>>()
                val perTile = HashMap<String, MutableList<Double>>()
                val excluded = LinkedHashMap<String, Int>()
                var reducedConf = 0
                val errCsv = StringBuilder("point_id,transect_id,lat,lon,ref_m,runtime_m,err_m,confidence\n")
                for (t in ts) {
                    val rows = pointRows.filter { it[1] == t.id }
                    prefetchFor(rt, rows.map { GeoPoint(it[2].toDouble(), it[3].toDouble()) })
                    for (r in rows) {
                        val lat = r[2].toDouble(); val lon = r[3].toDouble()
                        val ref = pointRef.getValue(r[0])
                        val s = rt.sample(lat, lon)
                        if (ref.isNaN()) { excluded.merge("reference_nodata", 1, Int::plus); continue }
                        if (s !is ElevationSample.Value) { excluded.merge("runtime_${(s as ElevationSample.Unavailable).reason}", 1, Int::plus); continue }
                        if (s.confidence < 1f) reducedConf++
                        val e = s.heightM - ref
                        errors += e
                        perTransect.getOrPut(t.id) { ArrayList() } += e
                        val tx = (WebMercator.worldPxX(lon, z) / 256).toInt(); val ty = (WebMercator.worldPxY(lat, z) / 256).toInt()
                        perTile.getOrPut("$z/$tx/$ty") { ArrayList() } += e
                        errCsv.append("${r[0]},${t.id},$lat,$lon,$ref,${s.heightM},$e,${s.confidence}\n")
                    }
                }
                File(gd, "gdata1_errors_z$z.csv").writeText(errCsv.toString())
                val g1 = dist(errors)
                val pass1 = errors.size > 0 && g1["p50"]!!.jsonPrimitive.double <= 0.3 && g1["p95"]!!.jsonPrimitive.double <= 1.0
                zr["gdata1"] = buildJsonObject {
                    put("points_declared", pointRows.size); put("points_scored", errors.size)
                    put("excluded", JsonObject(excluded.mapValues { JsonPrimitive(it.value) }))
                    put("reduced_confidence_samples", reducedConf)
                    put("abs_error_m", g1)
                    put("per_transect", JsonObject(perTransect.mapValues { dist(it.value) }))
                    put("worst_tiles_by_p95", buildJsonArray {
                        perTile.entries.filter { it.value.size >= 20 }.map { it.key to dist(it.value) }
                            .sortedByDescending { it.second["p95"]!!.jsonPrimitive.double }.take(5)
                            .forEach { (k, d) -> add(buildJsonObject { put("tile", k); put("stats", d) }) }
                    })
                    put("verdict_if_primary", if (pass1) "PASS" else "FAIL")
                }

                // ---------------- G-DATA-2 / 3 / 4 and U2
                val gradeDiffs = ArrayList<Double>()
                val rawGradeDiffs = ArrayList<Double>()
                val perRouteGrade = LinkedHashMap<String, JsonElement>()
                val routeReports = ArrayList<RouteMatchReport>()
                val featureReports = ArrayList<RouteMatchReport>()
                val lostSevere = ArrayList<JsonElement>()
                val attenuationPp = ArrayList<Double>()
                val elevAttenuation = ArrayList<Double>()
                val boundaryShift = ArrayList<Double>()
                val u2 = LinkedHashMap<String, JsonElement>()
                val routeDetails = LinkedHashMap<String, JsonElement>()
                var unscorable = 0
                for (t in ts) {
                    val index = RouteIndex.build(route(t))
                    prefetchFor(rt, t.vertices)
                    val ref = refProfiles.getValue(t.id)
                    val run = profiles(index, rt)
                    assertEquals(ref.grade.size, run.grade.size)
                    val diffs = ArrayList<Double>()
                    for (i in 0 until ref.grade.size) {
                        val a = ref.grade.gradeAt(i); val b = run.grade.gradeAt(i)
                        if (a == null || b == null) {
                            // profile ends (grade window outside the profile) are not unscorable positions
                            if (ref.grade.statusAt(i) != GradeStatus.OUT_OF_RANGE || run.grade.statusAt(i) != GradeStatus.OUT_OF_RANGE) unscorable++
                            continue
                        }
                        diffs += (b - a) * 100.0
                        val ra = ref.featureGrade.gradeAt(i); val rb = run.featureGrade.gradeAt(i)
                        if (ra != null && rb != null) rawGradeDiffs += (rb - ra) * 100.0
                    }
                    gradeDiffs += diffs
                    perRouteGrade[t.id] = dist(diffs)
                    val refEvents = detector.detect(ref.grade)
                    val runEvents = detector.detect(run.grade)
                    val rep = matcher.matchRoute(t.id, refEvents, ref.grade, runEvents, run.grade)
                    routeReports += rep
                    // G-DATA-4
                    val features = detector.detect(ref.featureGrade)
                    val frep = matcher.matchRoute(t.id, features, ref.featureGrade, runEvents, run.grade)
                    featureReports += frep
                    for (p in frep.truePositives) {
                        attenuationPp += (abs(p.reference.maxGrade) - abs(p.runtime.maxGrade)) * 100.0
                        elevAttenuation += abs(p.reference.elevationChangeM) - abs(p.runtime.elevationChangeM)
                        boundaryShift += maxOf(abs(p.diagnostics.startErrorM), abs(p.diagnostics.endErrorM))
                    }
                    (frep.falseNegatives + frep.borderlineReferenceUnmatched)
                        .filter { it.eventClass == GradeEventClass.SHORT && abs(it.maxGrade) >= 0.12 - 1e-9 }
                        .forEach { lostSevere += buildJsonObject { put("route", t.id); put("event", eventJson(it)) } }
                    routeDetails[t.id] = buildJsonObject {
                        put("eligible_reference", rep.eligibleReferenceEvents); put("reference_events", refEvents.size)
                        put("runtime_events", rep.runtimeEvents); put("tp", rep.tp); put("fp", rep.fp); put("fn", rep.fn)
                        put("borderline_matched", rep.borderlineMatched.size)
                        put("borderline_reference_unmatched", rep.borderlineReferenceUnmatched.size)
                        put("borderline_runtime", rep.borderlineRuntime.size)
                        put("precision", rep.precision); put("recall", rep.recall)
                        put("no_event_case", rep.noEventCase); put("requires_investigation", rep.requiresInvestigation)
                        put("unscorable_length_m", rep.unscorableLengthM)
                        put("fp_events", JsonArray(rep.falsePositives.map(::eventJson)))
                        put("fn_events", JsonArray(rep.falseNegatives.map(::eventJson)))
                        put("features", features.size); put("feature_tp", frep.tp); put("feature_fn", frep.fn)
                        put("feature_eligible", frep.eligibleReferenceEvents)
                    }
                    if (z == 15) u2[t.id] = u2Stats(ref, refEvents)
                }
                zr["unscorable_grade_positions"] = JsonPrimitive(unscorable)
                val g2 = dist(gradeDiffs)
                zr["gdata2"] = buildJsonObject {
                    put("abs_grade_diff_pp", g2)
                    put("per_transect", JsonObject(perRouteGrade))
                    put("supplementary_raw_L25_grade_diff_pp", dist(rawGradeDiffs))
                    put("verdict_if_primary", if (gradeDiffs.isNotEmpty() && g2["p95"]!!.jsonPrimitive.double <= 1.5) "PASS" else "FAIL")
                }
                val agg = matcher.aggregate(routeReports)
                zr["gdata3"] = buildJsonObject {
                    put("eligible_reference_events", agg.totalEligibleReferenceEvents)
                    put("runtime_events", agg.totalRuntimeEvents)
                    put("tp", agg.tp); put("fp", agg.fp); put("fn", agg.fn)
                    put("precision", agg.precision); put("recall", agg.recall); put("f1", agg.f1)
                    put("borderline_matched", routeReports.sumOf { it.borderlineMatched.size })
                    put("borderline_reference_unmatched", routeReports.sumOf { it.borderlineReferenceUnmatched.size })
                    put("borderline_runtime", routeReports.sumOf { it.borderlineRuntime.size })
                    put("no_event_case_routes", JsonArray(agg.noEventCaseRoutes.map { JsonPrimitive(it) }))
                    put("routes_requiring_investigation", JsonArray(agg.routesRequiringInvestigation.map { JsonPrimitive(it) }))
                    put("outcome", agg.outcome.name)
                    put("diagnostics_tp", buildJsonObject {
                        agg.startErrorM?.let { put("start_error_m_median", it.median); put("start_error_m_p95", it.p95) }
                        agg.maxGradeErrorPp?.let { put("max_grade_error_pp_median", it.median); put("max_grade_error_pp_p95", it.p95) }
                        agg.elevationChangeErrorM?.let { put("elev_change_error_m_median", it.median); put("elev_change_error_m_p95", it.p95) }
                        agg.iou?.let { put("iou_median", it.median); put("iou_p95", it.p95) }
                    })
                    put("per_route", JsonObject(routeDetails))
                }
                val ftp = featureReports.sumOf { it.tp }; val ffn = featureReports.sumOf { it.fn }
                val featureRecall = if (ftp + ffn == 0) null else ftp.toDouble() / (ftp + ffn)
                val allFeatures = featureReports.sumOf { it.truePositives.size + it.borderlineMatched.size + it.falseNegatives.size + it.borderlineReferenceUnmatched.size }
                val allMatched = featureReports.sumOf { it.truePositives.size + it.borderlineMatched.size }
                val medAtt = if (attenuationPp.isEmpty()) null else rank(attenuationPp.sorted(), 0.5)
                val pass4 = featureRecall != null && featureRecall >= 0.80 && medAtt != null && medAtt <= 2.0 && lostSevere.isEmpty()
                zr["gdata4"] = buildJsonObject {
                    put("feature_reference_config", featureRefConfig.id)
                    put("eligible_features", featureReports.sumOf { it.eligibleReferenceEvents })
                    put("feature_tp", ftp); put("feature_fn", ffn); put("feature_recall", featureRecall)
                    put("all_features", allFeatures); put("all_features_matched", allMatched)
                    put("median_peak_grade_attenuation_pp", medAtt)
                    put("peak_grade_attenuation_pp", dist(attenuationPp))
                    put("elevation_change_attenuation_m", dist(elevAttenuation))
                    put("boundary_shift_m", dist(boundaryShift))
                    put("tp_by_class", buildJsonObject {
                        for (c in GradeEventClass.entries) put(c.name, featureReports.sumOf { r -> r.truePositives.count { it.reference.eventClass == c } })
                    })
                    put("fn_by_class", buildJsonObject {
                        for (c in GradeEventClass.entries) put(c.name, featureReports.sumOf { r -> r.falseNegatives.count { it.eventClass == c } })
                    })
                    put("lost_severe_short_features", JsonArray(lostSevere))
                    put("verdict_if_primary", if (pass4) "PASS" else "REVIEW")
                }
                if (z == 15) results["u2_reference_statistics"] = JsonObject(u2)
                zr["cache"] = buildJsonObject {
                    val st = rt.cache.stats()
                    put("max_bytes", rt.cache.maxBytes); put("max_blocks", rt.cache.maxBlocks)
                    put("block_retained_bytes", DemBlock.RETAINED_BYTES); put("blocks_at_end", st.blocks); put("evictions", st.evictions)
                }
            }
            results["z$z"] = JsonObject(zr)
        }
        val json = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(results))
        File(gd, "gdata_results.json").writeText(json)
        outDir?.let { File(it, "gdata_results.json").apply { parentFile.mkdirs() }.writeText(json + "\n") }
        println("GData phase B: wrote ${File(gd, "gdata_results.json")}")
    }

    /** U2: relief/grade distributions on the reference (1 m NMT) FilterConfig-v1 profile. */
    private fun u2Stats(ref: Profiles, events: List<GradeEvent>): JsonObject {
        val f = ref.grade.filtered
        fun deltas(windowM: Double): List<Double> {
            val out = ArrayList<Double>()
            val k = (windowM / f.spacingM).toInt()
            for (i in 0 until f.size - k) {
                if (f.partIndexAt(i) != f.partIndexAt(i + k)) continue
                val a = f.heightAt(i) ?: continue
                val b = f.heightAt(i + k) ?: continue
                out += b - a
            }
            return out
        }
        val grades = (0 until ref.grade.size).mapNotNull { ref.grade.gradeAt(it) }.map { it * 100 }
        val bins = listOf(0.0, 2.0, 4.0, 7.0, 10.0, 15.0, Double.MAX_VALUE)
        return buildJsonObject {
            put("abs_dh_next_300m", dist(deltas(300.0)))
            put("abs_dh_next_600m", dist(deltas(600.0)))
            put("abs_grade_pct", dist(grades))
            put("abs_grade_histogram_pct_bins", buildJsonObject {
                for (i in 0 until bins.size - 1) {
                    val lo = bins[i]; val hi = bins[i + 1]
                    put(if (hi == Double.MAX_VALUE) ">=${lo.toInt()}" else "${lo.toInt()}-${hi.toInt()}", grades.count { abs(it) >= lo && abs(it) < hi })
                }
            })
            put("events_standard", events.count { it.eventClass == GradeEventClass.STANDARD })
            put("events_short", events.count { it.eventClass == GradeEventClass.SHORT })
            put("climbs", events.count { it.type == GradeEventType.CLIMB })
            put("descents", events.count { it.type == GradeEventType.DESCENT })
            put("event_length_m", dist(events.map { it.lengthM }))
            put("profile_length_m", f.distanceAt(f.size - 1))
        }
    }
}
