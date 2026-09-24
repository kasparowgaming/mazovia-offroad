# TA-000A — Terrain Ahead: Repository Audit (Phase 1)

Read-only audit. No implementation, design, file plan or task breakdown is contained here.
Labels: **VERIFIED** (file/symbol/command output inspected), **INFERRED** (reasoned from cited evidence),
**UNKNOWN / NOT PRESENT**.

---

## 0. Audit date and baseline

| Item | Value |
|---|---|
| Audit started | `2026-09-24T21:28:43+02:00` (from `date -Iseconds` at run start) |
| Repository root | `C:/AI_Projects/MazoviaOffroad` — `pwd` returned `/c/AI_Projects/MazoviaOffroad` (Git Bash notation of the same path), `git rev-parse --show-toplevel` returned `C:/AI_Projects/MazoviaOffroad` |
| Branch | `main` (`git branch --show-current`) |
| HEAD | `c9cd8b075201fd14f1f9e4a0a852802db6be0816` (`git rev-parse HEAD`) — matches expected baseline |
| `git status --short` before | empty (no modified or staged tracked files) |
| PRE-RUN untracked (non-ignored) list | empty (`git ls-files --others --exclude-standard` returned nothing) |
| `docs/terrain-ahead/AUDIT.md` before run | did not exist |

Ignored files present in the working tree (recorded for context, not touched): `.claude/`, `.gradle/`, `.kotlin/`,
`.opencode/`, `.tmp-gradle/`, `build/` dirs, `Test.class`, `abi.txt`, `os.txt`, `crash_test.txt`, `firenze.pmtiles`,
`meminfo_*.txt`, `window_dump.xml`, `*.log` (bench/fix3.2/route-benchmark), `logcat*.txt` (VERIFIED: `git status --short --ignored`).

Method: local sources read with `cat`/`sed`/`grep`; no Gradle, no tests, no formatters. Upstream sources were downloaded
only into the session scratchpad **outside** the repository.

---

## 1. Findings

### Module map (context for all findings)

- VERIFIED `settings.gradle.kts`: modules `:app`, `:domain`, `:data`, `:routing`, `:navigation`, `:designsystem`.
- VERIFIED: **no version catalog** (`gradle/` contains only `wrapper/`; no `libs.versions.toml`), **no lockfiles**
  (`ls *.lockfile gradle/*.lockfile */gradle.lockfile` → none). All versions are declared directly in each module's `build.gradle.kts`.
- VERIFIED root `build.gradle.kts`: AGP `8.7.3`, Kotlin `2.0.21`, KSP `2.0.21-1.0.28`. Gradle wrapper `8.9` (`gradle/wrapper/gradle-wrapper.properties`).
- VERIFIED module dependencies: `:navigation` → `:domain`; `:routing` → `:domain` + `graphhopper-core:9.1`;
  `:designsystem` → `:domain` + Compose; `:data` → `:domain` + Room; `:app` → all modules.
  `:domain` has no Android UI dependency, but `RidePackEvaluator` in `:domain` uses `android.content.Context` (VERIFIED `domain/src/main/java/pl/mazovia/offroad/domain/readiness/RidePackEvaluator.kt`).
- Paths requested in the brief: `app/`, `domain/`, `data/`, `routing/`, `navigation/`, `designsystem/`, `tools/tiles/`, `scripts/`, `.ai/` — all PRESENT.
  `scripts/` contains only `agent-status.ps1`, `local-agent.ps1` (agent workflow tooling; no data-pipeline scripts).

### F1. How RIDING receives navigation state; update cadence

- VERIFIED `app/.../ui/MazoviaNavHost.kt` `MazoviaNavHost`: `AppMode.RIDING` → `RidingScreen(navigationManager = app.navigationManager, ...)`.
- VERIFIED `app/.../ui/riding/RidingScreen.kt:45`: `val navState by navigationManager.navigationState.collectAsState()` —
  RIDING consumes a single `StateFlow<NavigationState>` from the application-scoped `NavigationManager`.
- VERIFIED `app/.../MazoviaOffroadApp.kt:82`: `navigationManager = NavigationManager(routingEngine, locationClient)` (manual DI, one instance per process).
- VERIFIED `navigation/.../NavigationManager.kt:37-47`: in `init`, a coroutine on `CoroutineScope(Dispatchers.Default)` collects
  `locationClient.getLocationUpdates(1000L)` and calls `updatePosition(point, bearing, speedMps)`. Exceptions are swallowed (`// Ignore for now`).
- VERIFIED `app/.../location/AndroidLocationClient.kt:36-38`: `LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).setMinUpdateIntervalMillis(intervalMs)`;
  callback delivered on `Looper.getMainLooper()`; only `result.locations.lastOrNull()` is forwarded.
- **Cadence**: position/bearing/speed are published at the GPS fix rate, nominally **1 Hz** (VERIFIED request interval 1000 ms, min interval 1000 ms).
  Every accepted fix produces a new `NavigationState` object (VERIFIED `NavigationManager.kt:223`). Actual delivered rate is device/fused-provider dependent — INFERRED, not measured.
- VERIFIED filtering inside `updatePosition` (`NavigationManager.kt:137-158`):
  - speed < 1.5 m/s is reported as 0;
  - a fix < 3.0 m from the last accepted position is replaced by the last accepted position (position hold);
  - bearing is held at the last accepted value when `speedMps == null || speedMps < 2.0`.
- Other concurrent location consumers (VERIFIED by grep of `getLocationUpdates(`): `MapViewModel` (2000 ms), `TrackRecordingService` (3000 ms),
  `RoughnessMeasurementCoordinator` (1000 ms, two call sites), `LoopScreen` (`.first()`).
- Map camera in RIDING (VERIFIED `MapLibrePMTilesPOCContainer.kt:281-330`): a `LaunchedEffect(centerRequest, currentPosition, bearing, isFollowMode, styleReady)`
  issues `animateCamera(..., 1000)` per position change; bearing is applied only when it changes by ≥ 3°; **no tilt/pitch is ever set**.
  There is no interpolation between fixes beyond MapLibre's 1000 ms camera animation.

### F2. Route geometry representation; OSM metadata on `RouteSegment`

- VERIFIED `domain/.../model/Route.kt`: `Route(id, origin, destination, segments: List<RouteSegment>, metrics, profile, waypoints, maneuvers, source: RouteSource, originalGpx: GpxData?)`;
  `allPoints = segments.flatMap { it.points }` (computed on each access, no caching).
- VERIFIED `domain/.../model/RouteSegment.kt`: fields `points: List<GeoPoint>`, `distanceMeters`, `surface: Surface`, `highway: HighwayType`,
  `trackType: TrackType = UNKNOWN`, `smoothness: Smoothness = UNKNOWN`, `access: AccessRestriction = UNKNOWN`,
  `roadDataConfidence: RoadDataConfidence = UNKNOWN`, `osmWayId: Long? = null`, `name: String? = null`, `hasSurfaceOrRoadClassDetail: Boolean = false`.
- Which fields are actually populated for **calculated routes** — VERIFIED `routing/.../engine/GraphHopperRoutingEngine.kt` `extractSegments` (lines 676-769):
  - path details requested: only `"surface"`, `"road_class"`, `"track_type"` (lines 463, 544);
  - segments are cut at the union of all path-detail interval boundaries; adjacent segments share their boundary vertex (`subList(fromIdx, toIdx + 1)`);
  - populated: `points`, `distanceMeters` (haversine sum), `surface`, `highway`, `trackType`, `hasSurfaceOrRoadClassDetail`, `roadDataConfidence`;
  - **never populated**: `smoothness` (always `UNKNOWN`), `osmWayId` (always `null`), `name` (always `null`), `access` (always `UNKNOWN`).
    Confirmed by grep: no main-source assignment of `osmWayId =`/`smoothness =` on `RouteSegment` exists.
    (`smoothness` is used for GraphHopper *weighting* via encoded values, VERIFIED `GraphHopperRoutingEngine.kt:182-196` and `weights/AndroidWeightingFactory.kt:115`, but is not exported to the route.)
- **GPX routes** — VERIFIED `domain/.../gpx/GpxRoute.kt` `GpxRoute.create`: one `RouteSegment` per GPX `trkseg`, all with `surface = UNKNOWN`, `highway = UNKNOWN`,
  no maneuvers, `source = IMPORTED_GPX`, `profile = ODKRYWCZY`; only single-track GPX accepted.
- Segment length is data-driven and variable (from a few metres to many km) — INFERRED from boundary logic; no maximum segment length is enforced.

### F3. Elevation on route points

- VERIFIED `GeoPoint(latitude, longitude, elevation: Double? = null)` (`domain/.../model/GeoPoint.kt`).
- **Calculated routes**: VERIFIED `GraphHopperRoutingEngine.convertToRoute` line 652: `elevation = if (point.ele.isNaN()) null else point.ele`.
  VERIFIED `initGraphHopper` (lines 115-121) configures only `graph.location`, `graph.dataaccess.default_type=RAM_STORE`, `import.osm.ignored_highways`;
  **no `graph.elevation.provider`** is set.
  Upstream (GraphHopper 9.1, see §4 row GH-1): default provider is `noop` → `hasElevation() == false`, and `BaseGraphNodesAndEdges.loadExisting`
  throws `"Configured dimension elevation=false is not equal to dimension of loaded graph elevation=true"` on a mismatch.
  → INFERRED: any graph that loads successfully in this app is 2D, so **calculated-route points have `elevation == null`**. The graph files themselves are not in the repo (UNKNOWN content).
- **GPX routes**: VERIFIED `domain/.../gpx/GpxParser.kt:105,112`: `<ele>` is parsed into `GeoPoint.elevation`. Vertical reference is whatever the GPX producer used — UNKNOWN / source-dependent
  (not verified upstream in this run).
- **Live GPS**: VERIFIED `AndroidLocationClient.kt:48`: `elevation = location.altitude` when `hasAltitude()`. Upstream Android docs (§4 row AND-1): altitude is
  "in meters above the WGS84 reference ellipsoid" (not MSL). NavigationManager does not use elevation for anything (VERIFIED: no reference in `NavigationManager.kt`).
- **Recorded rides**: VERIFIED `data/.../entity/TrackPointEntity.kt:26` `elevation: Double?`, filled from GPS (`RideRepository.kt:35,50,64`) → ellipsoidal GPS altitude.
- No DEM, elevation tiles, elevation sampler, grade computation or barometer use exists anywhere in main sources — VERIFIED by grep for
  `elevation|srtm|dem|TYPE_PRESSURE|pitch` (only the hits listed above). IMU: `AndroidMotionSensorSource` uses `TYPE_ACCELEROMETER` and
  `TYPE_GAME_ROTATION_VECTOR`/`TYPE_ROTATION_VECTOR`/`TYPE_GRAVITY` for vertical-acceleration roughness only (VERIFIED lines 22-26); no pitch output is exposed.

### F4. How `currentSegmentIndex`, position and bearing are maintained

VERIFIED `navigation/.../NavigationManager.kt`:
- Private state: `currentSegmentIndex`, `currentPointIndex`, `activeRoutePoints` (= `route.allPoints`), `activeRoutePointDistances` (cumulative haversine, GPX segment gaps excluded, lines 49-77), `maneuverIndices` (nearest vertex per maneuver).
- **Point snap** (lines 160-176): nearest **vertex** (not a projection onto the polyline) within `[currentPointIndex-5, currentPointIndex+200]` for calculated routes; full scan for GPX.
- **Segment index** (`findNearestSegmentIndex`, lines 306-323): nearest vertex among segments `[idx-2, idx+5]`; can move backwards.
- **Position**: `currentPosition` = the raw fix or the held last-accepted fix (3 m hold); it is **not** snapped to the route.
- **Bearing**: `currentBearing` = GPS bearing when speed ≥ 2 m/s, else last accepted GPS bearing. It is **not** derived from route tangent.
- `currentPointIndex` and distance-along-route are **not exposed** in `NavigationState`; `remainingDistanceMeters` is (lines 197-200),
  so distance-along = `totalDistance - remaining` is derivable — INFERRED.
- Off-route: `OffRouteDetector` (`domain/.../navigation/OffRouteDetector.kt`) — 50 m threshold, 3 consecutive readings, 30 m to recover; distance is to polyline segments using a lat/lon-linear projection.
- Status derivation: lines 212-221. Arrival: calculated routes `remainingDist < 50 || straightLineDest < 30`; GPX requires last index + < 30 m + not off-route.
- `reroute()` exists (lines 251-294) but has **no production caller** — VERIFIED grep `\.reroute\(` in `*/src/main/**` → none (only `NavigationManagerTest`).
  INFERRED: OFF_ROUTE → RECALCULATING is not triggered automatically in the shipped UI.
- `restoreSession()` and `SessionRepository.saveNavigationSession()` have **no production caller** — VERIFIED grep. INFERRED: navigation-session recovery after process death is not wired.
- `GpxNavigator` (`navigation/.../GpxNavigator.kt`) has **no caller** in main sources — VERIFIED grep. GPX following is handled inside `NavigationManager`.

### F5. Offline map / routing packs and `tools/tiles`

- **Routing graph**: VERIFIED `MazoviaOffroadApp.kt:76-79` loads `filesDir/graph` on `GlobalScope`. VERIFIED `ui/more/OfflineDataScreen.kt:38-52,131-172`: user picks a directory via
  `OpenDocumentTree`, contents are copied to `filesDir/graph_temp`, then `GraphHopperRoutingEngine.validateAndSwapGraph` swaps it to `graph` with `graph_backup` rollback.
  The graph is **built outside this repository** — no GraphHopper import script/config exists in the repo (NOT PRESENT: searched `tools/`, `scripts/`, root). Build parameters (OSM extract, region, elevation) are UNKNOWN.
- **Map tiles**: VERIFIED `MapLibrePMTilesPOCContainer.kt:87` and `RidePackEvaluator.kt:30`: single file `getExternalFilesDir(null)/mazowieckie_offroad.pmtiles`.
  No in-app installer/downloader for this file exists (VERIFIED grep for `pmtiles` in main sources: only these two readers) — INFERRED it is side-loaded (e.g. `adb push`).
- **Style**: VERIFIED `app/src/main/assets/mapstyles/mazovia_offroad_v1.json`: one source `pmtiles_source` of type `vector`, `url: "{PMTILES_URI}"`, `maxzoom: 14`;
  34 layers (1 background, 4 fill, 24 line, 4 symbol); source-layers `transportation`, `landcover`, `water`, `waterway`, `building`, `place`;
  glyphs `asset://map/glyphs/{fontstack}/{range}.pbf` (bundled: `Open Sans Semibold` 0-255, 256-511). No raster, raster-dem, hillshade or terrain entries (VERIFIED grep).
  `{PMTILES_URI}` is replaced at runtime with `pmtiles://file://<abs path>` (VERIFIED `MapLibrePMTilesPOCContainer.kt:99`).
- **`tools/tiles` builds today** (VERIFIED file contents):
  - `config.json` + `process.lua`: a **tilemaker** configuration (INFERRED from the `Layer/Attribute/Find/AttributeNumeric` Lua API and `settings` schema; tilemaker itself and its version are not in the repo — UNKNOWN).
    Output: vector tiles z6–14, basezoom 14, gzip, attribution "© OpenStreetMap contributors", name "Mazovia Offroad Siedlce Prototype".
    `transportation` carries `highway, surface, tracktype, access, motor_vehicle, motorcycle, ford, bridge, tunnel, layer, name, ref, smoothness, sac_scale, service`.
  - `generate_style.py`: regenerates `app/src/main/assets/mapstyles/mazovia_offroad_v1.json`.
  - `get_tile.py`, `inspect_pmtiles.py`, `inspect_pmtiles_z14.py`, `inspect_pmtiles_z14_full.py`: read-only inspection using the `pmtiles` and `mapbox_vector_tile` Python packages.
  - **No** elevation / DEM / raster tooling exists. Formats produced: PMTiles v3 containing gzip MVT only (INFERRED: tilemaker writes `.pmtiles` when the output filename has that extension; the actual command line is not in the repo — UNKNOWN).
- **Ride Pack**: VERIFIED `domain/.../readiness/RidePackEvaluator.kt` + `RidePackReadiness.kt`: a readiness *evaluation* (map file existence → `UNKNOWN` coverage, route artifact, graph loaded, surface data, permissions/GPS),
  not a packaged artifact. There is no pack manifest, versioning, checksum or coverage metadata (NOT PRESENT). `OfflineDataState` (`domain/.../model/OfflineDataState.kt`) defines graph download states but has no producer wired to real downloads (INFERRED: grep shows no downloader).

### F6. MapLibre version(s) and upstream capabilities

- Declarations (VERIFIED by reading every `build.gradle.kts`):
  - `app/build.gradle.kts`: `implementation("org.maplibre.gl:android-sdk:11.11.0")` — configuration `implementation`, module `:app`, applies to all app variants. **The only MapLibre declaration**.
  - No version catalog, no `resolutionStrategy`/`force` override, no other module declares MapLibre. Resolved version = 11.11.0 is INFERRED (no lockfile).
  - Also declared in `:app`: `org.osmdroid:osmdroid-android:6.1.20` (legacy engine still compiled; used by `MapViewContainer.kt` and `Dummy.kt`).
- Runtime engine selection: VERIFIED `RidingScreen.kt:251,493` hard-codes `MapEngine.MAPLIBRE_PMTILES_POC`; `MapScreen.kt:130` and `GpxScreen.kt:141` also use `MapLibrePMTilesPOCContainer`.
  `MapLibreViewContainer` (online OSM raster POC, `tile.openstreetmap.org`) is reachable only via the unused `MapEngine.MAPLIBRE` branch.
- Upstream capabilities for **11.11.0** (details and URLs in §4):
  - **Pitch**: supported, **max 60°** (`MapLibreConstants.MAXIMUM_PITCH = 60.0f`, `MAXIMUM_TILT = 60` at tag `android-v11.11.0`). VERIFIED upstream.
  - **raster-dem source**: supported (`RasterDemSource` exists at tag; style-spec SDK table: Android since 6.0.0, `mapbox`/`terrarium` encodings). **Custom encoding (redFactor…) not supported on Android.** VERIFIED upstream.
  - **hillshade layer**: supported (`HillshadeLayer` exists at tag). The newer hillshade algorithms and `color-relief` layer arrive only in 13.0.0. VERIFIED upstream.
  - **3D terrain (`terrain` style property, mesh draping)**: **NOT supported** in MapLibre Native Android — style-spec table shows ❌ for basic functionality, `source`, `exaggeration`, linking issue maplibre-native#252 "Terrain3D", which is **open** (API read on audit date). VERIFIED upstream.
  - **Custom layers**: `CustomLayer(String id, long host)` exists at tag — a native (C/C++) host pointer is required; no Java/Kotlin GL callback in this class. VERIFIED upstream (class source). Practical usability from Kotlin is INFERRED to require NDK code.
  - **PMTiles**: supported since 11.8.0 (changelog "Add PMTiles support (#2882)"). VERIFIED upstream.

### F7. Elevation over PMTiles

- **7a. Can PMTiles encode/serve an elevation payload?** VERIFIED upstream (PMTiles v3 spec): tile types include PNG (0x02), WebP (0x04), AVIF; metadata key
  `encoding: "terrarium"` is defined ("only meaningful for raster images in lossless compression"). So yes, lossless PNG/WebP Terrain-RGB or Terrarium tiles can be stored.
  The spec defines only `terrarium` as an `encoding` value; Mapbox Terrain-RGB would be stored as ordinary PNG with the decoding declared in the style — INFERRED.
- **7b. Can MapLibre 11.11.0 consume it on Android?** Partially VERIFIED / partially INFERRED:
  - VERIFIED: raster-dem + hillshade exist; the PMTiles file source at tag `android-v11.11.0` (`platform/default/src/mbgl/storage/pmtiles_file_source.cpp`) is tile-type-agnostic,
    builds a TileJSON (forces `scheme: xyz`, fills `tiles`, `minzoom`, `maxzoom` from the header) and supports only tile compression **none or gzip** (throws "Compression method not supported" otherwise).
  - VERIFIED upstream: in 11.11.0, style.json source options overriding TileJSON values (e.g. raster-dem `encoding` on a `url`-based source) were handled ad-hoc;
    PR maplibre-native#3570 "Fix for raster dem encoding override in style.json" was released in **11.13.1**.
    INFERRED: with 11.11.0 and a `pmtiles://` URL, a `terrarium` override may be ignored and the default `mapbox` decoding applied.
  - UNKNOWN: no end-to-end test of `raster-dem` from `pmtiles://file://` on Android exists in this repo or was run in this audit.
  - Regardless of 7b, the DEM can only drive **hillshade** (2D shading) on 11.11.0, not 3D terrain geometry (F6).
- **7c. Can the current offline pipeline package and resolve it without network?** NO as of baseline — VERIFIED: `tools/tiles` has no raster/DEM step; the style has a single vector source;
  the app resolves exactly one hard-coded PMTiles filename; `RidePackEvaluator` checks only that file. INFERRED: the `pmtiles://file://` resolution path itself is offline-capable
  (DECISION-003 in `.ai/DECISIONS.md` and `.ai/PROJECT_STATE.md` record offline rendering as verified — project claim, not re-verified here).
- **7d. Limitations**: Android 11.11.0: no 3D terrain; no custom raster-dem encoding; encoding override on TileJSON sources suspect until 11.13.1;
  PMTiles tile compression only none/gzip (no brotli/zstd); pitch capped at 60°; hillshade algorithm is the pre-13.0 one; quantized-mesh / GeoTIFF / custom binary
  are not MapLibre source types at all (INFERRED from style-spec source list: `vector`, `raster`, `raster-dem`, `geojson`, `image`, `video`). Lossy WebP/JPEG must not be used for DEM (PMTiles spec note).

### F8. `TerrainRadar` / `TerrainRadarCalculator`

- VERIFIED `domain/.../model/TerrainRadar.kt`: `TerrainRadar(segments: List<TerrainRadarSegment>, offRoadProportion, distanceToAsphaltMeters, asphaltConnectorLengthMeters, dataConfidence: DataConfidence, lookAheadMeters)`;
  `TerrainRadarSegment(surface, distanceMeters, confidence, fraction)`.
- VERIFIED `routing/.../terrain/TerrainRadarCalculator.kt`: pure function of `(route, currentSegmentIndex)`; `route.segments.drop(currentSegmentIndex)`, accumulates **whole** segments until
  `lookAheadMeters` is reached (can overshoot; includes the part of the current segment already behind the rider); computes off-road share (`RouteSegment.isOffRoad`), first asphalt connector
  (start distance + contiguous length), per-segment fractions, and averaged legacy `DataConfidence` (CONFIRMED iff `hasSurfaceOrRoadClassDetail`).
- **No geometry, no elevation, no curvature, no grade** is computed. It is surface/classification-only.
- Shareable with a terrain feature: the "segments ahead from `currentSegmentIndex`" walk and the surface/asphalt classification (`RouteSegment.isOffRoad`/`isAsphalt`, `Surface`) — INFERRED.
- Use in UI (VERIFIED `RidingScreen.kt:364-387, 569-594`): calculator created with `remember {}` but `calculate(...)` runs **in composition on every recomposition** (i.e. every `NavigationState` emission, ~1 Hz).
  Portrait uses `lookAheadMeters = 10000.0`; landscape uses the default `5000.0` — inconsistent.
- Rendering: `designsystem/.../components/TerrainRadarView.kt` — a 6 dp horizontal colour bar plus up to 4 labels.
- Tests: `routing/src/test/.../terrain/TerrainRadarCalculatorTest.kt` (4 tests: off-road proportion, asphalt connector, empty segments, current segment index).

### F9. Architectural constraints on where a terrain subsystem could live (constraints only)

- VERIFIED dependency direction: feature modules depend on `:domain`; `:app` is the only module that depends on MapLibre and on all others. MapLibre types are therefore only available in `:app`.
- VERIFIED `:designsystem` depends on Compose + `:domain` only (no MapLibre, no navigation).
- VERIFIED `NavigationState` is the only public navigation output; it lacks point index, distance-along, snapped position and route-tangent heading (F4). Anything needing those must derive them from `route` + `currentPosition`/`remainingDistanceMeters` without changing `NavigationManager` semantics.
- VERIFIED `NavigationManager` runs its tracking on `Dispatchers.Default`; `StateFlow` updates are consumed in Compose on the main thread; RIDING currently does per-emission work in composition (TerrainRadar, `allPoints` rebuild guarded by `remember(routeId)`).
- VERIFIED map containers own `MapView` lifecycle via `DisposableEffect` + `LifecycleEventObserver` and call `onDestroy()` on dispose (`MapLibrePMTilesPOCContainer.kt:347-365`). Only one MapView is created per RIDING layout.
- VERIFIED no DI framework (manual singletons in `MazoviaOffroadApp`). New long-lived services would need to be wired there or scoped to a composable.
- VERIFIED offline data locations are split: graph in `filesDir/graph` (internal), PMTiles in `getExternalFilesDir(null)` (external app-specific). No shared pack root exists.
- Product constraint (brief) + `.ai/DECISIONS.md` DECISION-003: no runtime network for map rendering. `NominatimPlaceSearchRepository` is the only network client in main sources (VERIFIED file present; search is a planning feature).

### F10. Classes carrying navigation semantics that must remain unchanged

See §3 (list with evidence).

### F11. Existing tests covering routing/navigation (inspected, not executed)

VERIFIED by `grep -c @Test` on every tracked `*/src/test/*.kt`:
- `:navigation`: `NavigationManagerTest` (7): initial IDLE, start → ON_ROUTE, stop → IDLE, restore → RECOVERED, ARRIVED near destination, reroute returns engine result, GPX gap → OFF_ROUTE without silent reroute.
- `:domain`: `OffRouteDetectorTest` (6), `GeoPointTest` (5), `RouteSegmentTest` (4), `RouteMetricsTest` (3), `SurfaceTest` (4), `RoadDataConfidenceTest` (5), `AccessRestrictionTest` (4),
  `LoopScoreTest` (3), `GpxParserTest` (5), `ExactGpxTest` (2), `RidePackEvaluatorTest` (5), roughness tests (22 total), search tests (6).
- `:routing`: `TerrainRadarCalculatorTest` (4), `GraphSwapTest` (7), `CalculateAlternativesConcurrencyTest` (1), `LoopPlannerTest` (15), `LoopGraphValidationTest` (1),
  `LoopGeoJsonExporterTest` (2), `MarginalTerrainEfficiencyTest` (14), `RouteMetricsContinuityTest` (5), `RouteTournamentTest` (8), `RouteTournamentCorridorsTest` (5),
  `UnionBoundarySegmentationTest` (6), `OffroadWeightingHelperTest` (7), benchmark/diagnostics (`BenchmarkTelemetryTest` 6, `RouteBenchmarkRunnerTest` 1, `RouteDiagnosticsTest` 3, `ClassificationDiagnosticsTest` 1).
  Some of these require a real graph (INFERRED from names `LoopGraphValidationTest`, `RouteBenchmarkRunnerTest`; not executed).
- `:app`: `MapViewModelTest` (6), `TrustWorkflowsTest` (6), `RoadConfidenceUiMapperTest` (5), `Task012AIntegrationTest` (2). `:data`: 2 saved-route tests.
- NOT PRESENT: any `androidTest` sources (none tracked); any test for `RidingScreen`, map containers, camera behaviour, or elevation.

### F12. Repository hygiene relevant to safe change

| Path | Tracked | What it is | Load-bearing? |
|---|---|---|---|
| `Test.java` | yes | Stand-alone `main` printing distance formatting (VERIFIED) | No — not in any source set; root project applies no Java/Kotlin plugin (VERIFIED root `build.gradle.kts` uses `apply false` only) |
| `Test.kt` | yes | Same as above; line 30 has a malformed string literal `"...TEXT: "" -> VALUE: "" UNIT: """` (VERIFIED) | No — not compiled (same reason). Would not compile if moved into a source set (INFERRED) |
| `Test.class` | no (ignored) | compiled `Test.java` | No |
| `fix_engine.py` | yes | One-shot rewrite of `GraphHopperRoutingEngine.calculateRoute` via regex, containing an **older** implementation (`val gh = graphHopper ?:`) (VERIFIED) | No. **Hazard**: re-running it would overwrite current routing code with an obsolete version referencing a field that no longer exists (current code uses `engineState`) — INFERRED |
| `fix_hopper.py`, `fix_weights.py` | yes | One-shot package renames from `com.mazoviaoffroad.app...` to `pl.mazovia.offroad...` (VERIFIED); already applied (current files use new packages) | No |
| `app/src/main/java/javax/lang/model/SourceVersion.java` | yes | Shim providing `SourceVersion.isKeyword` for GraphHopper 9.1 on ART (VERIFIED comment + code) | **Yes** — required at runtime by GraphHopper (per its own documentation); must not be removed |
| `app/src/main/java/.../ui/map/components/Dummy.kt` | yes | `fun test(mapView: org.osmdroid.views.MapView)` compile probe (VERIFIED) | No functional use; keeps an osmdroid reference in the app source set |
| `opencode.jsonc` | yes | Local LLM (llama.cpp, `127.0.0.1:8080`) agent config (VERIFIED) | No (tooling) |
| `task010_diff.patch` (≈244 KB), `TASK-007.md`, `TASK-008.md`, `routing/LOOP-FIX2.md` | yes | Historical task artifacts | No |
| `screen*.png`, `s_*.png`, `uidump*.xml`, `test.gpx`, `test_mazovia.gpx` | yes | Screenshots / UI dumps / sample GPX | No (not referenced by build) — INFERRED |
| `gradlew.bat` only | yes | No POSIX `gradlew` script tracked (VERIFIED `git ls-files`) | Build on non-Windows needs a local Gradle — INFERRED |
| `firenze.pmtiles`, `logcat*.txt`, `*.log`, `crash_test.txt` | no (ignored) | Local artifacts | No |

Commit history: `git log` for `Test.*`/`fix_*.py` shows only `2f9ba4b baseline: imported Mazovia Offroad project` (VERIFIED).

---

## 2. Reusable classes (existing, with evidence and limitations)

| Class | Location | Exposes | Limitations |
|---|---|---|---|
| `NavigationState` | `domain/.../model/NavigationState.kt` | status, route, currentPosition, currentBearing, currentSpeedMps, nextManeuver, distanceToNextManeuverMeters, remainingDistanceMeters, currentSegmentIndex, GPX return fields | No point index / distance-along / snapped position / route heading; ~1 Hz; bearing held < 2 m/s; position held < 3 m |
| `NavigationManager.navigationState` | `navigation/.../NavigationManager.kt:20` | `StateFlow<NavigationState>` | Single app-wide instance; emits new object every fix |
| `Route`, `RouteSegment` | `domain/.../model/` | Polyline (`allPoints`), per-segment surface/highway/trackType/confidence, maneuvers, source | `allPoints` recomputed per access; smoothness/osmWayId/name never populated; GPX segments carry no classification |
| `GeoPoint` | `domain/.../model/GeoPoint.kt` | lat/lon/elevation?, `distanceTo` (haversine), `bearingTo` | Double lat/lon; elevation null for calculated routes; init `require` on ranges |
| `Maneuver` / `ManeuverType` | `domain/.../model/Maneuver.kt` | point, type, distance, street name | `exitBearing` never set (VERIFIED `extractManeuvers`); none for GPX |
| `Surface`, `HighwayType`, `TrackType`, `Smoothness` | `domain/.../model/Surface.kt`, `RoadClassification.kt` | OSM tag mapping, `isOffRoad`, `isOffRoadCandidate` | `Surface.UNKNOWN.isOffRoad == true` but `RouteSegment.isOffRoad` falls back to highway when surface is UNKNOWN |
| `RoadDataConfidence` / `RouteDataConfidenceSummary` | `domain/.../model/` | per-segment evidence confidence | Only ROUTING_GRAPH source populated for calculated routes |
| `TerrainRadarCalculator` | `routing/.../terrain/TerrainRadarCalculator.kt` | surface look-ahead summary from `currentSegmentIndex` | Whole-segment granularity; no geometry/elevation; runs in composition |
| `TerrainRadarView` | `designsystem/.../components/TerrainRadarView.kt` | surface bar UI | 2D bar only |
| `OffRouteDetector` | `domain/.../navigation/OffRouteDetector.kt` | point-to-polyline distance (private) | Distance helper is private; lat/lon-linear projection |
| `MapLibrePMTilesPOCContainer` | `app/.../ui/map/components/` | Offline MapLibre view with route/GPS/destination/waypoint GeoJSON layers, follow camera | No pitch; 1 s animateCamera per fix; name still "POC" but is the production path |
| `RidePackEvaluator` / `RidePackReadiness` | `domain/.../readiness/` | MAP/route/graph/device readiness matrix | Map check = file existence only; no terrain component |
| `LocationClient` / `LocationUpdate` | `domain/.../location/LocationClient.kt` | point (incl. ellipsoidal altitude), speed, bearing, accuracy, elapsedRealtimeNanos, speedAccuracy | NavigationManager drops accuracy/timestamp |
| `AndroidMotionSensorSource`, roughness pipeline | `app/.../sensor/`, `domain/.../roughness/` | rotation-vector-projected vertical acceleration windows, per-ride calibration | Measures roughness, not pitch; no barometer |
| `tools/tiles/*` | `tools/tiles/` | tilemaker vector PMTiles config + style generator + PMTiles inspectors | Vector only; no DEM step |

---

## 3. Classes whose navigation semantics must remain unchanged

Evidence-based list (each defines or directly drives navigation behaviour consumed by RIDING):

- `NavigationManager` (`navigation/.../NavigationManager.kt`) — sole owner of snapping, segment index, off-route/recovered/arrived status, remaining distance/time, next maneuver, GPX return guidance, stop/start lifecycle.
- `NavigationState` / `NavigationStatus` (`domain/.../model/NavigationState.kt`) — public contract of the above; `@Serializable`.
- `OffRouteDetector` / `OffRouteState` (`domain/.../navigation/OffRouteDetector.kt`) — thresholds 50 m / 3 readings / 30 m.
- `GraphHopperRoutingEngine`, `AndroidGraphHopper`, `weights/*`, `profile/*`, `RouteTournament`, `LoopPlanner` (`routing/.../engine`, `.../profile`) — route calculation, selection, segmentation (`extractSegments`), maneuvers.
- `RoutingEngine` / `RoutingResult` (`domain/.../routing/RoutingEngine.kt`) — engine contract.
- `Route`, `RouteSegment`, `RouteMetrics`, `Maneuver`, `GeoPoint` (`domain/.../model/`) — serialized (kotlinx `@Serializable`) and persisted via saved routes (`data/.../RouteRepository.kt`, tests `LegacySavedRouteTest`, `SavedGpxRouteTest`).
- `GpxRoute`, `GpxParser`, `GpxWriter` (`domain/.../gpx/`) — GPX fidelity (`ExactGpxTest`).
- `LocationClient` / `AndroidLocationClient` — fix source for navigation, recording and roughness.
- `SourceVersion` shim (`app/src/main/java/javax/lang/model/SourceVersion.java`) — required by GraphHopper at runtime.
- `TrackRecordingService` + `SessionRepository` — ride/session lifecycle invoked from `RidingScreen.handleStopRide`.

---

## 4. Upstream capability check

Date consulted for all rows: **2026-09-24** (audit date, Europe/Warsaw, +02:00).

| ID | Library / format | Declared version in repo | Official URL consulted | Section / API / source symbol | Conclusion | Limitations |
|---|---|---|---|---|---|---|
| ML-1 | MapLibre Native Android | 11.11.0 (`app/build.gradle.kts`) | https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/CHANGELOG.md | `## 11.11.0`, `## 11.8.0` "Add PMTiles support (#2882)", `## 11.8.8`, `## 11.13.1`, `## 13.0.0` | 11.11.0 contains PMTiles support; no terrain feature in any Android release entry; hillshade algorithm update + color-relief only in 13.0.0 | Changelog is a summary, not an API contract |
| ML-2 | MapLibre Native Android | 11.11.0 | https://raw.githubusercontent.com/maplibre/maplibre-native/android-v11.11.0/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/constants/MapLibreConstants.java (tag commit `bf50262e`) | `MAXIMUM_PITCH = 60.0f`, `MAXIMUM_TILT = 60` | Pitch supported up to 60° | Horizon/sky not evaluated |
| ML-3 | MapLibre Native Android | 11.11.0 | `.../android-v11.11.0/.../style/sources/RasterDemSource.kt`, `.../style/layers/HillshadeLayer.java` | class `RasterDemSource` (URI/TileSet ctors), class `HillshadeLayer` | raster-dem + hillshade available | Visual quality/perf on target devices not tested |
| ML-4 | MapLibre Native Android | 11.11.0 | `.../android-v11.11.0/.../style/layers/CustomLayer.java` | `CustomLayer(String id, long host)`, `native initialize(String, long)` | Custom layers exist but take a native host pointer | Kotlin-only custom GL rendering not provided by this class (INFERRED) |
| ML-5 | MapLibre Style Spec (terrain) | n/a (runtime is ML Native 11.11.0) | https://maplibre.org/maplibre-style-spec/terrain/ | SDK Support table: basic / `source` / `exaggeration` | **Android ❌** (issue #252) — no 3D terrain | Table reflects current SDK, applies a fortiori to 11.11.0 |
| ML-6 | MapLibre Native issue | — | https://api.github.com/repos/maplibre/maplibre-native/issues/252 | `title: "Terrain3D"`, `state: "open"` | 3D terrain still not implemented | Status at audit date only |
| ML-7 | MapLibre Style Spec (sources) | n/a | https://maplibre.org/maplibre-style-spec/sources/ | `raster-dem` `encoding` = `terrarium`/`mapbox`/`custom`; SDK table | Android: raster-dem since 6.0.0; mapbox/terrarium supported; **custom encoding params ❌ (#2783)** | — |
| ML-8 | MapLibre Native core | 11.11.0 | https://raw.githubusercontent.com/maplibre/maplibre-native/android-v11.11.0/platform/default/src/mbgl/storage/pmtiles_file_source.cpp | header compression check (lines 248-252), TileJSON synthesis (`scheme`, `tiles`, `minzoom`, `maxzoom`) | PMTiles file source is tile-type-agnostic; tile compression none/gzip only | End-to-end raster-dem via `pmtiles://` not tested |
| ML-9 | MapLibre Native PR | fixed in 11.13.1 (not in 11.11.0) | https://api.github.com/repos/maplibre/maplibre-native/pulls/3570 | "Fix for raster dem encoding override in style.json", merged 2025-08-01 | In 11.11.0 raster-dem `encoding` override over TileJSON (`url`) sources is unreliable | Exact 11.11.0 behaviour INFERRED from PR description |
| PM-1 | PMTiles v3 format | archives produced by tilemaker (version UNKNOWN) | https://raw.githubusercontent.com/protomaps/PMTiles/main/spec/v3/spec.md | "Tile Type (TT)" table; "Compression"; metadata `encoding` | PNG/WebP/AVIF raster tiles allowed; `encoding: terrarium` defined for lossless DEM | `mapbox` Terrain-RGB not named in spec |
| GH-1 | GraphHopper | 9.1 (`routing/build.gradle.kts`, `app/build.gradle.kts`) | https://raw.githubusercontent.com/graphhopper/graphhopper/9.1/core/src/main/java/com/graphhopper/GraphHopper.java ; `.../storage/BaseGraphNodesAndEdges.java` ; `.../routing/Router.java` ; `.../util/PathMerger.java` | `createElevationProvider` default `"noop"`; `setElevationProvider` → `setElevation(false)`; `BaseGraphNodesAndEdges` lines 110-113 dimension check; `Router` `new PointList(..., nodeAccess.is3D())` | App config yields a 2D graph; a 3D graph would fail to load; route points carry no elevation | Graph files not in repo |
| AND-1 | Android `android.location.Location` | compileSdk 34 | https://developer.android.com/reference/android/location/Location | `getAltitude()` | "altitude of this location in meters above the WGS84 reference ellipsoid"; separate MSL altitude API exists | MSL API level not recorded here |

Not verified upstream in this run (UNKNOWN): GPX 1.1 `<ele>` vertical datum; tilemaker PMTiles output behaviour/version; GUGiK NMT formats, CRS, licence (context-only topics for TA-000B).

---

## 5. Critical unknowns and what would resolve each

1. **Does `raster-dem` from `pmtiles://file://` actually render (hillshade) on MapLibre Android 11.11.0, and with which encoding?** — Resolve with an on-device POC using a small lossless PNG Terrain-RGB (mapbox encoding) PMTiles; compare against a `terrarium` archive to confirm the ML-9 override issue; or evaluate an upgrade to ≥ 11.13.1.
2. **3D terrain path** — MapLibre Android has none (ML-5/ML-6). Whether pitch ≤ 60° + hillshade + GeoJSON route layers satisfies V1 "3D view, terrain from real elevation" is a product/acceptance question for the TA-002 gate.
3. **Routing graph provenance** — how `filesDir/graph` is built (OSM extract, date, profiles, encoded values, elevation) is not in the repo. Resolve by locating/recording the external GraphHopper import config and command.
4. **tilemaker version / command line / source extract** for `mazowieckie_offroad.pmtiles` — not recorded. Resolve by recording the build command and inputs alongside `tools/tiles`.
5. **Real GPS fix rate and bearing quality on the reference device** while four location subscribers are active — resolve with logged `elapsedRealtimeNanos` from a real ride.
6. **Vertical reference alignment** — GPS altitude is ellipsoidal (AND-1); GPX `<ele>` datum is producer-defined; a GUGiK DEM would be normal-height based (context). Resolve by documenting datum per source before any grade validation.
7. **Ride Pack structure** — no manifest/coverage/versioning exists; whether terrain data joins an existing pack concept cannot be answered from code. Resolve at design stage.
8. **Whether `reroute()` / `restoreSession()` are intentionally unwired** — resolve with the product owner; affects the OFF_ROUTE/RECALCULATING/RECOVERED states a terrain view would have to handle.

---

## 6. Risks visible from the current code

- **R1 — Location permission at process start** (INFERRED): `NavigationManager` starts collecting in `init` during `Application.onCreate`; `AndroidLocationClient` throws if permission is missing or GPS/network providers are disabled, the exception is swallowed, and collection is never restarted. On a fresh install RIDING may receive no positions until the process restarts.
- **R2 — Always-on 1 Hz high-accuracy GPS** (VERIFIED code, battery impact INFERRED): the NavigationManager subscription lives for the whole process, including PLANNING, alongside three other subscribers.
- **R3 — Per-fix work in composition** (VERIFIED): `TerrainRadarCalculator.calculate` runs in the RIDING composable on every emission; `allPoints` is a fresh `flatMap` per access (used in `NavigationManager.initializeRouteData` and `remember(routeId)` in RIDING).
- **R4 — Coarse progress signals** (VERIFIED): `currentSegmentIndex` is based on nearest vertex among variable-length segments and can move backwards; there is no projected position or distance-along in `NavigationState`. Anything visual keyed on it will jump.
- **R5 — Heading source** (VERIFIED): `currentBearing` is raw GPS bearing held below 2 m/s; no route-tangent heading exists.
- **R6 — Camera cadence** (VERIFIED): 1000 ms `animateCamera` per fix, bearing threshold 3°, no pitch; no frame-rate interpolation layer.
- **R7 — No elevation anywhere in routed data** (VERIFIED/INFERRED, F3): enabling elevation in GraphHopper would require a different graph *and* config (dimension check) — i.e. touching routing, which is out of bounds.
- **R8 — MapLibre 11.11.0 limits** (VERIFIED upstream): no 3D terrain, pitch ≤ 60°, raster-dem encoding override issue before 11.13.1, PMTiles compression none/gzip only.
- **R9 — GPX radar semantics** (INFERRED from `RouteSegment.isAsphalt` + `GpxRoute`): GPX segments have `surface = UNKNOWN` and `highway = UNKNOWN`, so `isOffRoad == false` and `isAsphalt == true`; the existing Terrain Radar presents imported GPX tracks as asphalt. Any shared use of this classification inherits that.
- **R10 — Hazardous root scripts** (VERIFIED/INFERRED, F12): `fix_engine.py` would overwrite `GraphHopperRoutingEngine.calculateRoute` with obsolete code if executed.
- **R11 — Unwired lifecycle paths** (VERIFIED grep): `reroute()`, `restoreSession()`, `saveNavigationSession()`, `GpxNavigator` have no production callers; state matrix behaviour in the field differs from what the enum suggests.
- **R12 — Single hard-coded map file** (VERIFIED): `mazowieckie_offroad.pmtiles` in external files dir, side-loaded, existence-only readiness; a missing/partial file yields a blank dark style with no route layers (the fallback branch at `MapLibrePMTilesPOCContainer.kt:203-223` never sets `styleReady`, so route/GPS layers are not drawn — VERIFIED).
- **R13 — Test coverage gaps** (VERIFIED): no instrumentation tests; no tests for RIDING UI, camera, map containers or any elevation logic.
