# TA-000B — Terrain Ahead: Technical Design

Design document only. No feature implementation was performed in this run.

Evidence labels: **VERIFIED** (opened in this run), **AUDIT** (from `AUDIT.md`, finding ID given), **INFERRED**, **UNKNOWN**.
Number labels: **TARGET** (proposed budget), **DERIVED** (formula shown), **MEASURED** (only from an inspected artifact),
**ESTIMATED** (assumption-based range, stated assumption).

---

## 0. Design baseline

| Item | Value |
|---|---|
| Design started | `2026-09-24T21:57:37+02:00` |
| Repository root | `C:/AI_Projects/MazoviaOffroad` (`pwd` → `/c/AI_Projects/MazoviaOffroad`, same path in Git Bash notation) |
| Branch | `main` |
| HEAD | `fb8ca376d1414a6ca8a63f59bc7d712ed5b896e2` (matches expected) |
| `AUDIT.md` git blob hash | `94d5d255ccfac4a76c351615683cb554bdab4651` (matches expected) |
| Tracked working tree before | clean (`git status --short` empty) |
| PRE-RUN untracked (non-ignored) | none |
| Source delta since audit baseline | VERIFIED `git diff --stat c9cd8b0 HEAD` → only `docs/terrain-ahead/AUDIT.md` added; every source file the audit inspected is byte-identical, so AUDIT findings on source apply unchanged. |

Local symbols this design depends on were re-opened in this session and are cited as VERIFIED:
`NavigationState.kt`, `NavigationManager.kt`, `Route.kt`, `RouteSegment.kt`, `GeoPoint.kt`, `GpxRoute.kt`, `RidingScreen.kt`,
`MapLibrePMTilesPOCContainer.kt`, `RidePackEvaluator.kt`, `MazoviaOffroadApp.kt`, `MainActivity.kt`, `AppModeManager.kt`,
`MapViewModel.kt` (`startNavigation`), `AndroidManifest.xml`, all module `build.gradle.kts`, `settings.gradle.kts`, `tools/tiles/*`.

### 0.1 TA-000B-C1 corrections

Correction pass started `2026-09-24T22:25:06+02:00` on HEAD `312dae97f07ed2cab66b5eacfa6f4d9963d2672f`
(`AUDIT.md` blob `94d5d255…`, pre-correction `DESIGN.md` blob `0bf3e413e50db4b8984a691c22e300afeff3cdf7`). Only this file changed.

- **C1 — long-route coordinate architecture corrected; NavigationManager-compatible cumulative distance made explicit.**
  Route-wide progress axis `s` = haversine cumulative distance over `route.allPoints` exactly as `NavigationManager` computes it
  (GPX gaps = 0); candidate search uses a coarse lat/lon grid; point-to-edge projection happens in an **edge-local** tangent frame
  (Strategy C, §6.2, §10.2). Render-scene coordinates (local ENU + floating origin) are a separate, unchanged concern.
- **C2 — elevation/profile processing made non-destructive.** Raw / Filtered / Display / Grade profiles are separate; anomaly
  detection emits metadata only; smoothing attenuation is derived and becomes a measured TA-001A deliverable; a short-steep event
  rule is pre-declared so short real features are not silently dropped (§12).
- **C3 — G-DATA event detection formally defined**: same detector for reference and runtime, one-to-one IoU matching, precision
  and recall ≥ 0.90, ≥ 50 eligible reference events (else INCONCLUSIVE), BORDERLINE band, NO_EVENT_CASE; G-DATA split into
  G-DATA-1…4 (§22.3).
- Battery measurement protocol clarified (charge counter, ≥ 3 + 3 trials, §21.5); n = 5 glance test declared exploratory (§3.5, §22.6).

---

## 1. Executive decision

1. **V1 visual mode — recommendation: Option C "Road-Ahead instrument"** (2D/2.5D strip: next 300–600 m profile, surface bands,
   grade markers, next maneuver), rendered with Compose Canvas. **True 3D (Option A) is not abandoned**: it stays behind gate
   **G-3D** (glance test + renderer spike) and becomes V1.1/V2 only if it measurably beats Option C in the one-second glance test.
   Decision remains with the operator (§26 OD-1, OD-2).
2. **The renderer-independent terrain core is built first and serves A and C**: `TerrainRouteProjection` → `ElevationSampler` →
   Raw/Filtered/Grade profiles → `GradeEvents`, in a new module `:terrain` (depends on `:domain` only). Route progress uses the
   NavigationManager-compatible haversine axis `s` with edge-local projection (C1); DEM data is never rewritten by filtering or
   anomaly detection (C2).
3. **Elevation**: GUGiK **NMT** (not NMPT), preprocessed offline into **PMTiles v3 / Terrain-RGB (mapbox encoding) lossless PNG,
   Web-Mercator XYZ z15, 256 px** (DERIVED ≈ 2.93 m ground spacing at 52.2° N). A minimal Kotlin PMTiles v3 reader is written
   against the CC0 spec with Planetiler's Apache-2.0 reader as reference. Format is re-checked at data gate **G-DATA**
   (G-DATA-1 DEM, G-DATA-2 grade, G-DATA-3 events, G-DATA-4 feature preservation, §22.3) against a custom-binary alternative.
4. **No frozen-class exceptions are requested.** Navigation, routing, GPX, session and readiness classes stay untouched.
   Terrain readiness is added beside, not inside, `RidePackEvaluator`.
5. **Renderer (only if Option A proceeds)**: spike **Filament (direct)** and **MapLibre GL JS in WebView** on an identical scene;
   SceneView, raw OpenGL ES and Vulkan are rejected for the spike (§13). MapLibre Native is **not** a terrain renderer; the
   standalone MapLibre-Native DEM POC is **removed** from the roadmap (§13.1).
6. **Next task: TA-001A** — projection (Strategy C) + synthetic elevation + raw/filtered/grade profiles + event detector +
   formal event matcher + smoothing-attenuation measurements, with unit tests; no real data, no rendering.

---

## 2. Inputs from TA-000A

### 2.1 Verified constraints

| # | Constraint | Source | Re-verified |
|---|---|---|---|
| C1 | RIDING consumes `NavigationManager.navigationState: StateFlow<NavigationState>` via `collectAsState()` | AUDIT F1 | VERIFIED `RidingScreen.kt:45` |
| C2 | Location requested at 1000 ms, high accuracy; one `NavigationState` per accepted fix; position held < 3 m; speed < 1.5 m/s → 0; bearing held when speed < 2 m/s | AUDIT F1 | VERIFIED `NavigationManager.kt:37-47,137-158`, `AndroidLocationClient.kt:36-38` |
| C3 | `NavigationState` has no projected position, distance-along, edge fraction, route tangent or fix timestamp | AUDIT F4 | VERIFIED `NavigationState.kt:9-24` |
| C4 | `StateFlow` conflates values equal by `equals()`; a stationary rider with held position/bearing/speed may produce **no emission** | new | VERIFIED upstream `kotlinx-coroutines-core/common/src/flow/StateFlow.kt` lines 51-57 ("Strong equality-based conflation") + `NavigationState` is a `data class` |
| C5 | `currentSegmentIndex` = nearest vertex among segments `[i-2, i+5]`, may go backwards; segments variable length | AUDIT F4 | VERIFIED `NavigationManager.kt:306-323` |
| C6 | `Route.allPoints` = `segments.flatMap { it.points }`; adjacent calculated segments share a boundary vertex; GPX segments are separate `trkseg`s (gaps) | AUDIT F2 | VERIFIED `Route.kt:22`, `GraphHopperRoutingEngine.kt:713`, `GpxRoute.kt:13-21` |
| C7 | Calculated routes populate `surface`, `highway`, `trackType`, `roadDataConfidence`; never `smoothness`, `osmWayId`, `name`, `access` | AUDIT F2 | VERIFIED `GraphHopperRoutingEngine.kt:731-746` |
| C8 | Route points have no usable elevation for calculated routes (2D graph) | AUDIT F3, GH-1 | VERIFIED `GraphHopperRoutingEngine.kt:115-121,652` |
| C9 | MapLibre Native Android 11.11.0 is the only MapLibre dependency (`:app`); pitch ≤ 60°; raster-dem + hillshade; PMTiles; no 3D terrain | AUDIT F6, ML-1..8 | VERIFIED `app/build.gradle.kts` |
| C10 | MapLibre Native decodes DEM samples to **integer metres** | new | VERIFIED upstream `src/mbgl/geometry/dem_data.cpp` @ `android-v11.11.0`, line 93 `static_cast<int32_t>(...)` |
| C11 | `MapView.onPause()` pauses only the renderer; `onStop()` stops the map; `setMaximumFps(int)` exists | new | VERIFIED upstream `MapView.java` @ `android-v11.11.0` lines 377-382, 387-400, 458 |
| C12 | MapLibre types live only in `:app`; modules `domain, data, routing, navigation, designsystem, app`; no DI framework | AUDIT F9 | VERIFIED `settings.gradle.kts`, module build files |
| C13 | All RIDING entries start with a route (`MapViewModel.startNavigation`, `ForestScreen`, `GpxScreen`, `LoopScreen`, `PointToPointScreen` call `switchToRiding()` after `startNavigation`/`startGpxFollowing`) | new | VERIFIED grep `switchToRiding` + `MapViewModel.kt:168-197` |
| C14 | `MainActivity` handles `orientation|screenSize|screenLayout|keyboardHidden` config changes itself (no Activity recreation on rotation) | new | VERIFIED `AndroidManifest.xml:30` |
| C15 | `minSdk = 26`, `compileSdk = 34` in all modules | new | VERIFIED module build files |
| C16 | Missing PMTiles → fallback style path never sets `styleReady` → route/GPS overlays never drawn | AUDIT R12 | VERIFIED `MapLibrePMTilesPOCContainer.kt:203-223` |
| C17 | No IMU pitch output, no barometer pipeline; GPS altitude is WGS84-ellipsoidal | AUDIT F3, AND-1 | VERIFIED `AndroidMotionSensorSource.kt:22-26` (earlier this session) |

### 2.2 Unknowns carried forward

| # | Unknown | Resolved by |
|---|---|---|
| U1 | Real GPS fix rate/jitter on the reference device | TA-001C log or TA-005 measurement |
| U2 | Elevation-difference distribution along real Mazovia routes (low relief is presumed, not measured) | TA-001B route-profile statistics (§12.5) |
| U3 | Quasi-geoid separation range for Mazovia (official PL-geoid model values) | TA-001B: sample the official GUGiK quasi-geoid grid over the region bbox (official page could not be retrieved in this run, §7.5) |
| U4 | Exact NMT accuracy figures and per-sheet horizontal CRS of downloaded files | TA-001B: read sheet metadata + regulation of 16 Dec 2022 (§7.3) |
| U5 | Whether Android `BitmapFactory` decodes Terrain-RGB PNG bit-exactly (colour management, premultiplication) | TA-001B test (§8.4 VALIDATION) |
| U6 | Real PNG compression ratio of Terrain-RGB for Mazovia NMT | TA-001B measurement |
| U7 | Filament AAR `minCompileSdk` vs app `compileSdk 34` (Filament builds with compileSdk 37) | TA-002 (§13.2) |
| U8 | WebView range/random-access to local PMTiles | TA-002 (§13.4) |
| U9 | Routing-graph and map-PMTiles build provenance | TA-008 (AUDIT §5 items 3-4) |

### 2.3 Audit findings intentionally not fixed here

Listed in §27. None of them is designed around; the state matrix (§16) only records how Terrain reacts if those paths become reachable.

---

## 3. V1 visual definition

Context shared by all options (VERIFIED `RidingScreen.kt:153-410`): portrait RIDING already shows status pills, maneuver card,
waypoint row, a map box (`weight(1f)`), Terrain Radar bar, data panel and action buttons. Any option replaces or overlays **only
the map box** content.

### 3.1 Option A — true 3D

Terrain mesh + road ribbon + active route + camera behind/above rider.

- Glance content: road curvature ahead, relative rise/fall, surface colour, maneuver location in space.
- Sunlight: shaded 3D relief on a small screen is low-contrast; relief perception depends on lighting and exaggeration (INFERRED).
- Low-relief Mazovia: if typical rises over 300–600 m are a few metres (U2, UNKNOWN), a true-scale mesh is visually almost flat;
  vertical exaggeration is then required, which distorts perceived steepness (INFERRED).
- Cost: new renderer dependency, mesh/chunk streaming, camera interpolation, GPU/thermal load, lifecycle — highest (§13, §21).
- Failure modes: renderer init/driver failure, stutter at 1 Hz input, thermal throttling, misleading exaggeration.
- Risk: HIGH.

### 3.2 Option B — pitched map

MapLibre Native, pitch ≤ 60° (C9), hillshade from a raster-dem source, surface-styled route, grade info as overlay.

- Glance content: familiar map + perspective; hillshade shows landform, not road gradient.
- Low-relief: hillshade on flat terrain gives little signal; MapLibre Native quantises DEM to 1 m (C10), eroding subtle slopes (INFERRED).
- Cost: low-medium (style + raster-dem archive), but TA-000A caveat on encoding override (AUDIT ML-9).
- Failure modes: same as MAP (AUDIT R12 blank view if archive missing).
- Risk: LOW technically, but it is essentially "MAP with shading" — adds little beyond MAP (INFERRED).

### 3.3 Option C — road-ahead instrument

A dedicated instrument: the next 300–600 m (TARGET) of the **route itself**, straightened into a strip.

```text
   +0 m                    +300 m                   +600 m
   ▲ rider
   ────▁▁▂▃▄▅▅▄▃▂▁▁▁───────────────▂▃▃▂──────   ← smoothed elevation profile (vertical scale fixed, labelled)
   [SZUTER    ][ASFALT][DUKT          ][PIACH]  ← surface bands from RouteSegment.surface
        ↑ +6% 120 m        ↱ 340 m               ← grade event marker, next maneuver marker
```

- Glance content: "climb/flat/descent ahead, how far, what surface, where the turn is" — exactly the product questions.
- Sunlight: flat high-contrast shapes and large text; no lighting dependence (INFERRED).
- Low-relief: vertical axis can be scaled with an explicit label (e.g. "±5 m"), so small but real changes are legible without
  pretending they are mountains (INFERRED).
- Cost: Compose `Canvas` in `:designsystem` + presenter in `:app`; no new native dependency.
- Failure modes: missing DEM → profile band shows "no data" hatch; surface bands and maneuver still work (graceful).
- Risk: LOW. Does not show off-route lateral terrain or junction geometry (V2).

### 3.4 One-second glance comparison

| Question the rider must answer | A (3D) | B (pitched map) | C (instrument) |
|---|---|---|---|
| Turn direction / where | good (spatial) | good | good (marker + distance), weaker for shape |
| Climb / flat / descent ahead | medium (depends on exaggeration/lighting) | weak | strong (explicit profile) |
| Surface transitions | medium (ribbon colour) | medium (route colour) | strong (bands) |
| Junction ahead | good (V2 data) | good (map) | weak (V2) |
| Sunlight readability | weak–medium | medium | strong |
| GPU / battery / thermal | high | medium (as MAP) | minimal |
| Implementation risk | high | low | low |

All cells are INFERRED expectations to be tested with §22.6; none is MEASURED.

### 3.5 Recommendation

```text
RECOMMENDATION   V1 = Option C (road-ahead instrument) on top of the shared terrain core.
                 (G-3D margin below is a PRODUCT GATE TARGET from an exploratory n ≥ 5 test, not statistical proof — §22.6.)
                 Option A remains behind gate G-3D; if it passes, it ships as V1.1 (post-V1), otherwise V2 or dropped.
                 Option B is not pursued for Terrain; a hillshade layer in MAP is a separate optional backlog item.
WHY              - answers the product questions directly, including in low relief;
                 - lowest battery/thermal/GPU cost; no new native dependency;
                 - reuses 100 % of TA-001A/B core, so no work is wasted if A follows;
                 - MapLibre Native cannot provide A (C9) and B adds little beyond MAP.
REJECTED         A as V1 (high risk before glance evidence); B (little glance value, DEM quantised to 1 m).
INVALIDATION     G-3D: in the §22.6 glance test on static mock-ups, A answers "climb/flat/descent" and "turn" correctly
                 ≥ 10 percentage points more often than C (TARGET margin) at equal error rate on other questions,
                 AND the TA-002 spike meets the mid-range budget (§21.1).
                 A smaller difference or conflicting per-question results = INCONCLUSIVE → operator decides (§22.6).
```

True 3D status: **moves behind gate G-3D (V1.1 or V2)**; not abandoned.

### 3.6 What would change the recommendation

- G-3D glance results favour A (above).
- TA-001B statistics show strong relief on target routes (e.g. many ≥ 10 m changes within 300 m — TARGET threshold), making spatial terrain more informative.
- Operator product decision that the 3D "wow" is itself a V1 requirement (OD-2).

**Cheap mock-up procedure (before any renderer spike)**: render 12 static frames per option (same 12 route snippets from TA-001B real
profiles, mixed turns/climbs/surfaces) as images on a desktop tool (any renderer/drawing tool, outside the app), show them on the
target phone in the handlebar mount with §22.6 protocol. Cost: mock images only; no app code.

---

## 4. Product architecture

### 4.1 Terrain Ahead responsibilities

- Derive presentation-only route progress (projection, distance-along, tangent) from `NavigationState` + `Route`.
- Load offline elevation, sample it into an immutable raw route profile, derive filtered/grade/display profiles and grade events
  without rewriting the raw data (§12).
- Produce a presentation state (profile window, surface bands, maneuver marker, grade events, confidence) at a bounded rate.
- Render it (V1: Compose Canvas instrument; later: 3D renderer).
- Report terrain data readiness separately from navigation readiness.

### 4.2 Non-responsibilities

Route calculation/selection, GPS acquisition, GPX navigation, off-route detection, recovery/reroute, maneuver generation,
session/recording lifecycle, navigation readiness gating, fixing unrelated audit bugs (§27).

### 4.3 Data-flow diagram

```text
 NavigationManager.navigationState (StateFlow, ~1 Hz, conflated)        [frozen, :navigation]
          │ collect (view-scoped, Dispatchers.Default)
          ▼
 TerrainRouteProjection  ── per route.id: RouteIndex (haversine axis s — NavigationManager-compatible,
          │                    coarse lat/lon edge grid, per-edge local-frame constants)            [:terrain]
          │ ProjectionResult(distanceAlong s, edge, fraction, tangent, crossTrack, mode)
          ▼
 RawElevationProfile (immutable, per route.id, windows filled ahead)  ◄── ElevationSampler ◄── DEM archive (PMTiles)
          │ ──► ProfileAnomalyDetector (metadata only, never rewrites)                            [:terrain]
          ▼
 FilteredElevationProfile ──► GradeProfile ──► GradeEventDetector                                [:terrain]
          │ window [s-50 m, s+600 m] (TARGET)
          ▼
 DisplayElevationProfile (presentation-only) + events ──► TerrainPresentationState (immutable)   [:terrain]
          │ StateFlow, ≤ 1 Hz + on route change
          ▼
 Presenter / interpolation (view-scoped, :app)  ──► Canvas instrument @ ≤ 30 fps while animating  (Option C)
                                               └─► 3D renderer thread @ 30–60 fps               (Option A, gated)
```

Update rates (TARGET):

| Stage | Rate |
|---|---|
| GPS / navigation input | ~1 Hz nominal (C2), can be 0 Hz when stationary (C4) |
| Projection + profile window + grade | on each navigation emission (≤ 1 Hz) and on route change |
| Presentation state | ≤ 1 Hz |
| Instrument animation (C) | 30 fps only while interpolating between states; idle otherwise |
| 3D render loop (A) | 30 fps min / 60 fps preferred |
| Terrain chunk updates (A) | every ≥ 64 m of progress (TARGET) |

---

## 5. Module architecture

### 5.1 Current dependency graph (VERIFIED build files)

```text
:app ──► :domain, :data, :routing, :navigation, :designsystem, maplibre 11.11.0, osmdroid, graphhopper-core 9.1
:routing ──► :domain, graphhopper-core 9.1
:navigation ──► :domain
:data ──► :domain, room
:designsystem ──► :domain, compose
:domain ──► coroutines, serialization
```

### 5.2 Proposed dependency graph

```text
:app ──► (existing) + :terrain
:terrain ──► :domain, kotlinx-coroutines-core            (no Compose, no MapLibre, no GraphHopper)
:designsystem ──► :domain  (unchanged deps; gains a pure UI component taking a UI model)
:routing, :navigation, :data ──► unchanged
```

Proof that `:routing`/`:navigation` stay unaware: their `build.gradle.kts` files are not in the file plan (§24) and gain no
`project(":terrain")`; `:terrain` depends on `:domain` only, so no cycle can make routing/navigation types depend on terrain;
the only place both meet is `:app` (composition root), which already depends on everything (C12).

### 5.3 Module responsibility table

| Module | Terrain Ahead responsibility |
|---|---|
| `:domain` | Nothing new in V1. Existing `Route`, `RouteSegment`, `GeoPoint`, `NavigationState` are read-only inputs. |
| `:terrain` (new, Android library for `BitmapFactory` access in TA-001B) | local frame, projection, route index, elevation sampler API + implementations, PMTiles reader, DEM cache, profile, grade, presentation state, terrain readiness model |
| `:designsystem` | `RoadAheadInstrument` composable (pure drawing from a UI model), MAP/TERRAIN segmented switch component |
| `:app` | wiring: collect `NavigationState`, own ride-/view-scoped terrain objects, locate archive files, map presentation state → UI model, RIDING switch; later 3D renderer host |
| `tools/terrain/` (new) | offline GUGiK → PMTiles pipeline, provenance manifest |

---

## 6. TerrainRouteProjection

### 6.1 Input/output contract

```kotlin
class TerrainRouteProjection(indexBuilder: (Route) -> RouteIndex) {   // RouteIndex = Strategy C (§6.2)
    fun onNavigationState(state: NavigationState, nowNanos: Long): ProjectionResult
}
data class ProjectionResult(
    val routeId: String?, val mode: ProjectionMode,          // ATTACHED, DETACHED, HOLD, NO_ROUTE
    val distanceAlongM: Double?,                             // navigation-compatible haversine axis s
    val edgeIndex: Int?, val edgeFraction: Double?,
    val projected: GeoPoint?, val tangentBearingDeg: Double?, val crossTrackM: Double?,
    val confidence: Float                                    // 0..1, presentation only
)
```

Pure Kotlin, no Android types, deterministic for a given input sequence. Never writes to `NavigationManager`.

### 6.2 Projection algorithm

(Corrected in TA-000B-C1 / C1. The original draft put the whole route into one ENU plane anchored at the first point and derived
`s` from it; that is replaced below.)

#### 6.2.1 Three coordinate responsibilities (kept separate)

| Role | Representation | Authority |
|---|---|---|
| **Route-wide progress axis** | `s` = cumulative **haversine** distance (`GeoPoint.distanceTo`, R = 6 371 000 m) over `route.allPoints` in order; GPX `trkseg` breaks add 0 | authoritative progress; must equal `NavigationManager`'s axis |
| **Local matching coordinates** | per-edge local tangent frame (spherical, same R), double | cross-track, edge fraction, tangent — never used to accumulate `s` |
| **Render-scene coordinates** | local ENU + floating origin (§10.3), float32 on GPU | presentation only |

NavigationManager axis (VERIFIED `NavigationManager.kt:49-60, 197-200`): `activeRoutePointDistances[i]` = Σ haversine over
`route.allPoints` with `i ∉ gpxBreaks`; `remainingDistanceMeters = last − activeRoutePointDistances[currentPointIndex]`.
The compatible identity is therefore `s(vertex i) = activeRoutePointDistances[i] = lastCumulative − remainingDistanceMeters`
(when navigation is snapped to vertex i). `route.totalDistanceMeters` (`RouteMetrics.fromSegments` sum, or `GpxData` track sum —
VERIFIED `RouteMetrics.kt:64`, `GpxData.kt:17`) is a haversine sum over the same points grouped differently; it agrees with
`lastCumulative` only up to floating summation-order rounding (INFERRED). `allPoints` of calculated routes contains each shared
segment-boundary vertex twice (C6); the duplicate contributes 0 m to both axes, and `RouteIndex` keeps vertex indices identical to
`allPoints` indices so vertex `i` means the same point in both systems.

#### 6.2.2 Distortion of a single route-wide tangent plane (why the old draft was only mildly wrong)

Sphere, tangent plane at the route origin, horizontal (E, N) components. A point at great-circle distance d maps to planar radius
R·sin(d/R); local radial scale = cos(d/R) ≈ 1 − (d/R)²/2, tangential scale ≈ 1 − (d/R)²/6 (DERIVED):

| Distance from origin | Radial scale error | Position displacement d − R·sin(d/R) | Planar-`s` drift, straight radial route (d³/6R²) | Cross-track error on a 60 m offset |
|---|---|---|---|---|
| 10 km | 1.2 × 10⁻⁶ (0.0001 %) | 0.004 m | 0.004 m | < 0.001 m |
| 50 km | 3.1 × 10⁻⁵ (0.003 %) | 0.51 m | 0.51 m | 0.002 m |
| 100 km | 1.2 × 10⁻⁴ (0.012 %) | 4.1 m | 4.1 m | 0.007 m |
| 300 km | 1.1 × 10⁻³ (0.11 %) | 110.9 m | 110.9 m | 0.07 m |

(DERIVED; route *length* ≥ distance from origin, so a 300 km loop stays well inside the 300 km row.)
Conclusion: a single plane is accurate enough for **matching** at every Mazovia route size; its real defect is **`s` drift** if `s`
is accumulated from planar edge lengths (metres at 100 km, ~111 m at 300 km) versus the navigation axis. The correction is
proportional: keep simple planar maths locally, take `s` from haversine.

#### 6.2.3 Strategies evaluated

**Strategy A — route-wide projected CRS.** Scale factor of a transverse Mercator k ≈ k₀·(1 + x²/2R²), x = distance from central
meridian, 1° longitude ≈ 68.5 km at 52° N (DERIVED). Over 19.0–23.5° E (INFERRED approximate span of Mazovia-area routes):
UTM 34N (CM 21°, k₀ 0.9996) → k from −0.040 % (CM) to −0.004 % (23.5° E); PL-1992 (CM 19°, k₀ 0.9993) → −0.070 % (19° E) to
+0.047 % (23.5° E) (DERIVED). UTM 34N distorts less over Mazovia; PL-1992's only advantage is matching GUGiK's source CRS, which
is irrelevant on device (DEM is reprojected offline, §7.4). Both need a transverse-Mercator implementation (Krüger series, compact,
~100 lines, no library required; or a projection library dependency). Any projected-CRS edge length differs from haversine by the
scale factor (up to 0.07 % → 70 m per 100 km), so `s` must still come from haversine → the CRS would be used only for matching,
where Strategy C needs no projection at all.

**Strategy B — segmented local ENU blocks.** Blocks of 2–5 km (hypothesis) each with an ENU anchor; `s` separate. Accurate, but
needs block-boundary handling (edges straddling blocks, candidates from two blocks in different frames, loops/crossings where two
distant route parts share a place but live in different blocks), a block lookup layer and extra tests. Error benefit over C: none.

**Strategy C — haversine `s` + coarse lat/lon grid + edge-local projection.**
1. `s` per vertex exactly as NavigationManager (haversine, GPX gaps 0), same point order and indices.
2. Coarse grid in lat/lon with cell ≈ 50 m (Δlat = 50/111 195°, Δlon = Δlat/cos φ̄, φ̄ = route mean latitude); each edge inserted
   into every cell its bounding box touches; grid only proposes candidates — query radius padded by 10 % (TARGET) to absorb the
   cos φ̄ approximation over the route's latitude span.
3. Per edge, precomputed constants: start lat/lon, cos(lat₀), planar edge vector (dx, dy) in the edge-local tangent frame at its
   start, planar length, haversine length `hᵢ`, `sᵢ`.
4. Per candidate: point → edge-local frame (2 subtractions, 2 multiplications), clamp-projected parameter `t ∈ [0,1]`,
   cross-track distance, tangent bearing from (dx, dy).
5. `distanceAlong = sᵢ + t · hᵢ` (edge fraction applied to the navigation-compatible edge length).

Per-edge local-plane error: for edges ≤ 2 km and offsets ≤ 60 m, (ℓ/R)²-order terms are < 10⁻⁷ relative (DERIVED from the table
above at 1–2 km) — negligible. Memory: ~9 doubles per edge ≈ 72 B → 30 000 edges ≈ 2.2 MB plus grid (DERIVED). Per-fix cost:
O(k) candidates, no trigonometry at query time.

#### 6.2.4 Decision

```text
RECOMMENDATION  Strategy C for TA-001A.
WHY             exact agreement with the NavigationManager progress axis by construction (same formula, same indices);
                no route-wide plane, no block state machine, no projection library; loops/crossings are just more
                candidates; deterministic; bounded per-fix work.
DERIVED ERROR   s: identical to navigation axis (only floating rounding, §6.6 tolerance); matching: < 1 mm-level
                planar error per edge; single-plane alternative would have drifted 4.1 m (100 km) / 111 m (300 km) in s.
REJECTED        A (needs TM implementation for no matching benefit; its edge lengths differ from haversine by up to 0.07 %);
                B (boundary complexity, no accuracy gain); original single-plane draft (s drift).
INVALIDATION    NavigationManager changes its distance semantics (e.g. ellipsoidal or projected lengths) — then s must follow;
                edges longer than ~20 km appear (planar edge-local error grows as ℓ²) — then split long edges virtually;
                measured per-fix cost > 1 ms on mid-range (§6.5).
```

Per update (unchanged scoring logic, now in edge-local terms):
1. Candidates = edges from grid cells within radius `R = 60 m` (TARGET; > `OffRouteDetector` 50 m threshold, VERIFIED `OffRouteDetector.kt:10`).
2. For each candidate: edge-local projection → `crossTrack`, `sAlong = sᵢ + t·hᵢ`.
3. Score = `crossTrack + λ·max(0, |sAlong − sExpected| − window)` with `sExpected = sPrev + v·Δt`, `window = 30 m + v·Δt·0.5`,
   `λ = 0.5` (all TARGET). The continuity term prevents snapping to parallel or later parts of loops/crossings.
4. Pick minimum; apply continuity rules (§6.3).

### 6.3 Continuity and hysteresis

- Backward movement ≤ 8 m (TARGET, > position hold 3 m + GPS noise) → keep `sPrev` (mode HOLD).
- Backward > 8 m accepted only after 2 consecutive consistent fixes (U-turn / real reversal).
- Forward jump > `v·Δt + 50 m` (TARGET) accepted only after 3 consistent fixes (re-acquisition after tunnel/GPS jump); until then
  the continuity-constrained candidate wins.
- `crossTrack > 60 m` for 2 fixes or `status == OFF_ROUTE` → mode DETACHED (§6.4).
- Re-attach: `crossTrack ≤ 30 m` (matches `OffRouteDetector` on-route threshold) with the best candidate chosen **without**
  continuity penalty but restricted to `sAlong ≥ sPrevAttached − 100 m` (TARGET) to avoid re-attaching far behind on loops.

### 6.4 GPX / loops / crossings / off-route

| Case | Behaviour |
|---|---|
| Calculated route | as §6.2 |
| GPX (`FOLLOWING_GPX`) | edges per `trkseg`; gaps never projected onto; while in a gap region the nearest valid edge is used and confidence ↓ |
| Loop start = end | continuity term keeps the early part; final approach resolves by `sExpected` |
| Self-crossing | both edges are candidates; continuity term chooses; ties → smaller `|sAlong − sExpected|` |
| `OFF_ROUTE` | DETACHED: projection frozen at last attached `s`; presentation shows off-route mode (§16) |
| `ROUTE_RECOVERED` / `ON_ROUTE` after off-route | re-attach rule §6.3 |
| `RECALCULATING`, `ROUTING_ERROR` | DETACHED (currently unwired, AUDIT F4) |
| `route.id` changes | discard index and state; rebuild; mode NO_ROUTE until built |
| `RECOVERED` | treat as ATTACHED initialisation with no `sPrev` (unwired) |
| `ARRIVED` | HOLD at end |
| `IDLE` / `route == null` | NO_ROUTE |
| GPS loss / stationary | no emission (C4) → projection unchanged; presentation handles staleness (§15.5) |

On any conflict (e.g. projection says attached while `status == OFF_ROUTE`) **navigation status wins**.

### 6.5 Complexity

- Build: O(n) haversine evaluations + O(n) grid insertion; memory ≈ 72 B per edge (§6.2.3) + grid
  (DERIVED: 10 000 points ≈ 0.7 MB, 30 000 points ≈ 2.2 MB + grid).
- Update: O(k), k = edges in ≤ 9 cells (typically < 50), no trigonometry per query — sub-millisecond (INFERRED; measured in TA-001A DoD).

### 6.6 Tests (TA-001A)

straight line; gentle curve; hairpin (two edges 10 m apart, opposite directions); X-crossing; 20 m parallel out-and-back section;
closed loop (start = end); backward jitter 5 m (HOLD) and 15 m ×1 (rejected) ×2 (accepted); forward jump 200 m ×1 (rejected)
×3 (accepted); off-route 80 m for 2 fixes → DETACHED, return at 20 m → re-attach; GPX with 2 `trkseg` and 300 m gap; route
replacement mid-ride; determinism (same input sequence → identical outputs).

Long-route and axis tests (C1):
- 100 km realistic synthetic route (≈ 10 000 vertices, curves, a loop, a crossing, a 300 m parallel out-and-back) and a 300 km
  stress route (≈ 30 000 vertices, 3° longitude span).
- **Axis agreement**: for every vertex i, `|s_index(i) − s_navRef(i)| ≤ 1 mm`, where `s_navRef` is a test-side reference that
  reproduces `NavigationManager.kt:53-60` verbatim (haversine via `GeoPoint.distanceTo`, same order, GPX breaks = 0).
  Tolerance justification (DERIVED): identical formula and order give bit-identical sums; the 1 mm budget only absorbs a different
  but mathematically equivalent summation (≈ 3·10⁴ terms × 2.2·10⁻¹⁶ × 3·10⁵ m ≈ 2·10⁻⁶ m), while any planar or projected `s`
  would violate it by metres (§6.2.2).
- Between vertices: `s = sᵢ + t·hᵢ` with `t` from the edge-local projection; for a point placed exactly on an edge at known
  haversine fraction, `|Δs| ≤ 1 cm` (TARGET) on edges ≤ 2 km.
- No drift: `s(last vertex)` equals the reference last cumulative within 1 mm on both 100 km and 300 km routes.
- GPX: 3 `trkseg` with 300 m gaps — gap contributes exactly 0; no candidate edge crosses a gap.
- Projection correctness at route start, route end, grid-cell boundaries (points exactly on cell edges), loops, crossings and
  parallel edges (continuity term chooses the expected branch).
- A later :app-level cross-check (TA-006) compares against a real `NavigationManager` instance: after `updatePosition` at vertex i,
  `lastCumulative − remainingDistanceMeters == s_index(i)` within 1 mm, where `lastCumulative` is the `remainingDistanceMeters`
  published immediately after `startNavigation` (VERIFIED `NavigationManager.kt:90`).

---

## 7. Elevation source

### 7.1 GUGiK dataset

VERIFIED https://www.geoportal.gov.pl/pl/dane/numeryczny-model-terenu-nmt/ (2026-09-24):
- NMT = "cyfrowa reprezentacja ukształtowania powierzchni terenu".
- Basic model: **1 m × 1 m grid**, systematically updated from **airborne laser scanning (ALS)**; for cities also stereo
  measurement from ≤ 10 cm orthophoto production. A **5 m × 5 m** NMT exists from stereo measurement (25 cm orthophoto).
  Legal basis cited on the page: regulation of 16 December 2022.
- Height systems: **PL-KRON86-NH** (data 2000–2019) and **PL-EVRF2007-NH** (data from 2018).
- Access: Geoportal "Pobierz dane", WMS/WMTS/WCS/WFS index services (e.g. `.../PZGIK/NumerycznyModelTerenuEVRF2007/WFS/Skorowidze`),
  WMS request examples use `CRS=EPSG:2180` (PL-1992). Export to GeoTIFF shown; ARC/INFO grid mentioned.
- Licence: "Dane NMT są dostępne bezpłatnie i możliwe do dowolnego wykorzystania."

### 7.2 NMT vs NMPT

VERIFIED https://www.geoportal.gov.pl/pl/dane/numeryczny-model-pokrycia-terenu-nmpt/: NMPT represents terrain **with objects above
it: buildings, trees, bridges, viaducts** (0.5 m urban, 1.0 m elsewhere). Terrain Ahead needs the riding surface → **NMT**.
NMPT would put forest canopy (tens of metres) into forest tracks — GUS: forest and wooded land = **23.8 %** of Mazowieckie
(VERIFIED GUS yearbook chapter "Warunki naturalne", Tabl. 2, 2025). NMPT is rejected.

### 7.3 Resolution and coverage

- Source resolution 1 m (VERIFIED). Runtime target z15 ≈ 2.93 m (DERIVED §9) — 1 m is resampled down.
- Coverage for Mazowieckie in the EVRF2007 series and year per sheet: UNKNOWN; resolved in TA-001B from the WFS index.
- Vertical accuracy figures: UNKNOWN (not stated on the page); resolve from the 16 Dec 2022 regulation and sheet metadata (U4).
- Forest: ALS ground classification under canopy is the intended NMT product, but local accuracy under dense canopy is
  UNKNOWN — TA-001B compares forest vs open-field profile noise.

### 7.4 CRS

| Frame | Role |
|---|---|
| PL-1992 (EPSG:2180) | GUGiK service CRS (VERIFIED in WMS example); likely sheet CRS (INFERRED, U4) |
| PL-2000 (zones) | older datasets may use it; pipeline must read CRS from each file, never assume |
| WGS84 (EPSG:4326) | route/GPS coordinates (`GeoPoint`) |
| Web Mercator (EPSG:3857) | runtime tile grid (§8.4) |
| Local ENU (metres) | rendering and projection maths (§10) |

Horizontal transform PL-1992/2000 → 3857 happens **offline** in the pipeline (GDAL-class tooling), never on device.

### 7.5 Vertical reference

- NMT heights: **normal heights** in PL-EVRF2007-NH (or PL-KRON86-NH for older sheets) — VERIFIED system names.
- GPS altitude: height above the **WGS84 ellipsoid** (AUDIT AND-1).
- Difference = quasi-geoid height anomaly (ζ), spatially varying; **no single constant** is used. Official GUGiK quasi-geoid model
  pages could not be retrieved in this run (`gov.pl/web/gugik/modele-quasi-geoidy` returned only navigation content; a geoportal URL
  returned 404) → regional range **UNKNOWN (unverified upstream)**. TA-001B must obtain the official model grid and report ζ
  min/max over the Mazowieckie bbox.
- Implications:
  - **absolute validation** (DEM vs GPS altitude): requires ζ correction per location; residual GPS vertical noise remains large;
  - **relative differences** over ≤ 1 km: ζ changes slowly, so the offset largely cancels (INFERRED; verify by ζ gradient in TA-001B);
  - **grade**: depends on differences over 20–30 m → datum offset is irrelevant; noise and smoothing dominate.
- Pipeline must **not mix** KRON86 and EVRF2007 sheets in one archive; mixed years → pick EVRF2007, record per-tile source year.
- Rendering never uses ellipsoidal heights (§10.4).

### 7.6 Licence and attribution

- GUGiK NMT: free, any use (VERIFIED §7.1). Attribution text is not prescribed on the page; design adds "Dane wysokościowe:
  GUGiK (NMT)" in the terrain archive metadata and in the app's data-sources screen (courtesy + provenance, INFERRED good practice).
- Map vector data: © OpenStreetMap contributors / ODbL (VERIFIED `tools/tiles/config.json` attribution). Terrain archive contains
  no OSM data; if V3 adds OSM-derived land cover, ODbL attribution applies.

### 7.7 Preprocessing pipeline

```text
WFS index (EVRF2007) ─► sheet list for pack bbox/corridor ─► download 1 m sheets (record URL, year, checksum)
  ─► mosaic (VRT) ─► read source CRS per sheet ─► reproject to EPSG:3857, bilinear/average resample to z15 grid
  ─► nodata mask (never 0 m) ─► encode Terrain-RGB mapbox (0.1 m) PNG 256 px, no colour chunks, no alpha
  ─► PMTiles v3 (tile type PNG, tile compression none, internal gzip) ─► metadata JSON ─► build manifest + sha256
```

Location: `tools/terrain/` (new). Required provenance (fixes AUDIT §5 items 3-4 pattern for terrain):
`build_manifest.json` with tool names + versions (GDAL, go-pmtiles/other writer, script git SHA), exact command lines, input sheet
IDs/URLs/years/sha256, bbox or corridor definition, output sha256, zoom, encoding, datum, build timestamp. Builds are scripted
(one command), deterministic given the same inputs; the manifest is committed next to published artifacts, artifacts are not
committed (`*.pmtiles` is git-ignored, VERIFIED `.gitignore`).

---

## 8. Runtime elevation format

### 8.1 Format candidates

1. **PMTiles v3 + Terrain-RGB PNG** (Web Mercator XYZ).
2. **Custom binary tiles**: header (base elevation, scale) + 16-bit offsets, zstd/deflate, own index, metric grid (PL-1992 or ENU tiles).
3. **Quantized mesh** (terrain mesh tiles).
4. **Runtime GeoTIFF** (COG).

### 8.2 Decision matrix (1 = poor, 5 = good; INFERRED scoring with evidence)

| Criterion | PMTiles/Terrain-RGB | Custom binary | Quantized mesh | GeoTIFF |
|---|---|---|---|---|
| Random tile access | 5 (spec directories) | 4 (own index) | 4 | 3 (COG internal tiling, complex) |
| Android decode complexity | 3 (PNG via `BitmapFactory`, U5) | 5 (ByteBuffer) | 2 | 1 (no maintained Android GeoTIFF decoder identified) |
| CPU per tile | 3 (PNG inflate + RGB unpack) | 5 | 3 | 2 |
| Memory | 4 | 5 | 4 | 3 |
| Disk size | 3 (RGB 3 B/sample before PNG) | 4 (2 B/sample + delta coding) | 4 | 3 |
| Preprocessing complexity | 4 (standard GDAL + pmtiles tooling) | 2 (all custom) | 2 | 5 |
| Renderer independence | 5 | 5 | 2 (mesh-oriented) | 5 |
| MapLibre compatibility | 5 (Native raster-dem, GL JS terrain) | 1 | 1 (not a MapLibre source type, AUDIT F7d) | 1 |
| Sharing with future map features | 5 (hillshade in MAP, GL JS candidate) | 1 | 2 | 2 |
| Ecosystem / licence | 4 (spec CC0, reference impls BSD-3; Planetiler Apache-2.0) | 2 | 3 | 3 |
| Maintenance risk | 4 | 2 | 3 | 2 |
| Exact metric grid (e.g. 4.00 m) | 2 (Mercator: 2.84–3.01 m at z15 over Mazovia) | 5 | 4 | 5 |

### 8.3 PMTiles reader question

VERIFIED upstream (2026-09-24):
- `protomaps/PMTiles` README lists consumers for JavaScript, Go, Python, serverless — **no Java/Kotlin reader**. Spec is public
  domain/CC0; reference implementations BSD-3 (VERIFIED `LICENSE`).
- `onthegomap/planetiler` (Apache-2.0, v0.10.2, active: pushed 2026-09-23) contains `planetiler-core/.../pmtiles/ReadablePmtiles.java`
  (212 lines) + `Pmtiles.java` (392 lines): reads via `FileChannel`/`SeekableByteChannel`, no HTTP assumption, but depends on
  planetiler-core types (`TileArchiveMetadata`, JTS `Coordinate`, etc.) — not usable as an Android dependency as-is (INFERRED from imports).
- In-app, MapLibre's reader is native and internal (AUDIT F7) — not callable from Kotlin.

Comparison:

| | Minimal PMTiles v3 reader (Kotlin) | Different container (custom) |
|---|---|---|
| Size | header (127 B) + varint directory decode + gzip + leaf dirs + run-length: small, bounded (INFERRED ~300 lines) | index + tile layout + writer tooling |
| Spec stability | v3 spec, fixed header | self-owned |
| Tooling | existing writers (go-pmtiles CLI, others) | must write writer too |
| Test oracle | archives from reference writer + Python `pmtiles` reader already used in `tools/tiles/*.py` (VERIFIED) | none |

Decision: write the **minimal read-only PMTiles v3 reader** in `:terrain` (local seekable file only, tile compression `none`/`gzip`,
internal compression `gzip` only — same subset MapLibre Native supports, AUDIT ML-8), port-guided by Planetiler (Apache-2.0,
attribution in source header) and verified against archives produced by the reference writer.

### 8.4 Recommended format

```text
RECOMMENDATION  PMTiles v3, tile type PNG, tile compression none, internal compression gzip;
                Terrain-RGB "mapbox" encoding (h = R·6553.6 + G·25.6 + B·0.1 − 10000; 0.1 m step —
                VERIFIED MapLibre dem_data.cpp unpack vector);
                Web Mercator XYZ, z15, 256 px (DERIVED ≈ 2.93 m ground spacing at 52.2° N);
                nodata = dedicated reserved colour + tile-level metadata mask (never 0 m, §11.4).
WHY             keeps BOTH renderer paths open while the renderer is undecided (Filament via Kotlin sampler;
                GL JS/Native raster-dem directly); standard tooling; single-file random access; same subset as
                MapLibre's reader; mapbox encoding avoids AUDIT ML-9 override issue (default encoding).
REJECTED        custom binary (best decode/size, but zero ecosystem, blocks MapLibre-based candidates, more tooling);
                quantized mesh (renderer-specific, useless for sampler/Option C);
                GeoTIFF at runtime (no maintained Android decoder identified; heavy).
```

VALIDATION NEEDED LATER (TA-001B):
```text
COMMAND:  unit/instrumented test decoding every tile of a test archive on device with BitmapFactory
          (inPremultiplied=false, ARGB_8888, no colour-space conversion) and comparing to encoder output
WHY:      Android may apply colour management / premultiplication (U5)
PROVES:   bit-exact Terrain-RGB round trip; if it fails → use a pure-Kotlin PNG inflate path or switch to Option 2
```

### 8.5 What would invalidate the decision

- U5 round-trip not bit-exact and no cheap workaround.
- TA-001B p95 decode time per tile > 15 ms on mid-range (TARGET §21) with no caching remedy.
- Measured corridor archive size > 2× custom-binary size and > Ride Pack budget (§21.3).
- Operator selects Option C only and rules out any MapLibre-based renderer permanently → MapLibre compatibility loses weight;
  re-run matrix (custom binary may win).

---

## 9. Storage and tiling

### 9.1 2 m / 4 m / 8 m calculations (DERIVED RAW)

Formulas: `samples/km² = 1 000 000 / spacing²`; 16-bit raw bytes = samples × 2; Terrain-RGB raw pixel bytes = samples × 3.

| Spacing | samples/km² | 16-bit raw / km² | RGB raw / km² |
|---|---|---|---|
| 2 m | 250 000 | 0.50 MB | 0.75 MB |
| 2.928 m (z15 @ 52.2°) | 116 639 | 0.233 MB | 0.350 MB |
| 4 m | 62 500 | 0.125 MB | 0.1875 MB |
| 5.856 m (z14 @ 52.2°) | 29 160 | 0.058 MB | 0.087 MB |
| 8 m | 15 625 | 0.031 MB | 0.047 MB |

Web Mercator ground spacing (DERIVED `156543.034 / 2^z · cos(lat)` m/px, 256 px tiles):
z14 = 6.01 / 5.85 / 5.68 m and z15 = 3.01 / 2.93 / 2.84 m at 51.0° / 52.25° / 53.5° N.
**An exact 4 m grid is not available in standard XYZ**; z15 (~2.9 m) or z14 (~5.9 m) are the candidates. Recommendation z15;
TA-001B also builds z14 and compares grade error (gate G-DATA).

Overheads (ESTIMATED): PMTiles header 127 B; directories typically « 1 % of tile payload for dense archives; metadata JSON < 10 KB.
Compression (ESTIMATED, assumption): PNG on Terrain-RGB of smooth low-relief terrain 1.5–3× vs RGB raw; delta-coded 16-bit with
zstd 2–4× vs 16-bit raw. Both to be MEASURED in TA-001B (U6). WORST-CASE = raw + 1 %.

### 9.2 Mazowieckie upper-bound calculation

Area: **3 555 861 ha = 35 558.61 km²** (VERIFIED GUS, Statistical Yearbook of Mazowieckie Voivodship, chapter "Warunki naturalne",
Tabl. 2 "Powierzchnia geodezyjna", as of 1 Jan 2025, https://warszawa.stat.gov.pl/dane-o-wojewodztwie/wojewodztwo/).

| Spacing | Samples (DERIVED) | 16-bit raw | RGB raw (WORST-CASE PMTiles) | RGB est. compressed 1.5–3× |
|---|---|---|---|---|
| 2 m | 8.89 × 10⁹ | 17.78 GB | 26.67 GB | 8.9–17.8 GB |
| 2.93 m (z15) | 4.15 × 10⁹ | 8.30 GB | 12.44 GB | 4.1–8.3 GB |
| 4 m | 2.22 × 10⁹ | 4.44 GB | 6.67 GB | 2.2–4.4 GB |
| 5.86 m (z14) | 1.04 × 10⁹ | 2.07 GB | 3.11 GB | 1.0–2.1 GB |
| 8 m | 5.56 × 10⁸ | 1.11 GB | 1.67 GB | 0.56–1.1 GB |

Bbox-to-polygon and tile-edge overhead is extra (UNKNOWN until built). Conclusion: whole-region terrain at ~3 m is multi-GB →
**corridor-based packs** are the realistic delivery unit (INFERRED).

### 9.3 50 km corridor calculations

Assumptions: straight corridor area = length × total width (DERIVED lower bound); tile-quantised area ≈ length × (width + T),
T = storage tile side (DERIVED heuristic for a route aligned with the grid; diagonal/winding routes vary). Values below use T = 1 km
as a conservative reference; z15 tiles are 0.75 km (DERIVED 256 × 2.928 m).

| Corridor | Spacing | Area | Samples | 16-bit raw | RGB raw | RGB tile-quantised (T = 1 km) raw |
|---|---|---|---|---|---|---|
| 50 km × 0.5 km | 2 m | 25 km² | 6.25 M | 12.5 MB | 18.75 MB | 56.25 MB (75 km²) |
| | 2.93 m | 25 km² | 2.92 M | 5.83 MB | 8.75 MB | 26.2 MB |
| | 4 m | 25 km² | 1.56 M | 3.13 MB | 4.69 MB | 14.1 MB |
| | 8 m | 25 km² | 0.39 M | 0.78 MB | 1.17 MB | 3.5 MB |
| 50 km × 1 km | 2 m | 50 km² | 12.5 M | 25.0 MB | 37.5 MB | 75.0 MB (100 km²) |
| | 2.93 m | 50 km² | 5.83 M | 11.7 MB | 17.5 MB | 35.0 MB |
| | 4 m | 50 km² | 3.13 M | 6.25 MB | 9.38 MB | 18.8 MB |
| | 8 m | 50 km² | 0.78 M | 1.56 MB | 2.34 MB | 4.7 MB |

Estimated compressed (1.5–3×) for the recommended z15 PNG: 50 × 0.5 km → 8.7–17.5 MB tile-quantised; 50 × 1 km → 11.7–23.3 MB.

### 9.4 100 km corridor calculations

| Corridor | Spacing | Area | Samples | 16-bit raw | RGB raw | RGB tile-quantised (T = 1 km) raw |
|---|---|---|---|---|---|---|
| 100 km × 0.5 km | 2 m | 50 km² | 12.5 M | 25.0 MB | 37.5 MB | 112.5 MB (150 km²) |
| | 2.93 m | 50 km² | 5.83 M | 11.7 MB | 17.5 MB | 52.5 MB |
| | 4 m | 50 km² | 3.13 M | 6.25 MB | 9.38 MB | 28.1 MB |
| | 8 m | 50 km² | 0.78 M | 1.56 MB | 2.34 MB | 7.0 MB |
| 100 km × 1 km | 2 m | 100 km² | 25.0 M | 50.0 MB | 75.0 MB | 150.0 MB (200 km²) |
| | 2.93 m | 100 km² | 11.7 M | 23.3 MB | 35.0 MB | 70.0 MB |
| | 4 m | 100 km² | 6.25 M | 12.5 MB | 18.8 MB | 37.5 MB |
| | 8 m | 100 km² | 1.56 M | 3.13 MB | 4.69 MB | 9.4 MB |

Estimated compressed (1.5–3×) for recommended z15 PNG: 100 × 0.5 km → 17.5–35 MB; 100 × 1 km → 23.3–46.7 MB tile-quantised.
With z15's real T = 0.75 km the quantised areas shrink (100 × 1 km → 175 km² → 61.2 MB RGB raw, DERIVED).
Corridor width TARGET: **500 m total** for Option C (profile needs only the route line ± sampling margin) and **1 000 m** for
Option A (lateral terrain view). Option C could even use a 100 m corridor; 500 m keeps headroom for GPS-offset/off-route context.

### 9.5 Storage tiles vs render chunks

| Concept | Definition | Size (TARGET/DERIVED) | Why independent |
|---|---|---|---|
| Storage tile | PMTiles XYZ z15 tile, 256 × 256 samples | ≈ 750 m side (DERIVED) | IO/compression unit; larger = fewer seeks, better compression |
| Decoded height block | one storage tile decoded to `FloatArray(256·256)` | 256 KB (DERIVED 65 536 × 4 B) | cached CPU form shared by sampler, profile and mesh builder |
| Render chunk (A only) | square in local ENU, 256 m side, 64 × 64 quads at 4 m | 4 225 vertices, 8 192 triangles (DERIVED 65², 64²·2) | view/culling/LOD unit; smaller = finer streaming along the route |
| GPU mesh | vertex + index buffers for one chunk | ≈ 150 KB (DERIVED: 4 225 × 24 B + 24 576 idx × 2 B ≈ 101 + 49 KB) | upload/replace unit |

A render chunk samples heights through `ElevationSampler`, not by copying tiles, so it can straddle storage tiles and use a different
spacing. Option C uses no chunks at all: it samples only the route line.

### 9.6 Tile addressing

Standard XYZ (`z/x/y`, xyz scheme; PMTiles Hilbert tile IDs internally). `ElevationSampler` converts WGS84 → Web Mercator pixel
coordinates at z15 (double), tile = floor(px/256), in-tile = px mod 256.

---

## 10. Coordinate system

### 10.1 Source coordinates

Routes/GPS: WGS84 double lat/lon (`GeoPoint`, VERIFIED). DEM: Web Mercator z15 tiles (§8.4). NMT source: PL-1992/2000 (offline only).

### 10.2 Local metric frame

Scope after TA-000B-C1: this section covers **render-scene** coordinates only. Route-wide progress (`s`, haversine) and
local matching (edge-local frames) are defined in §6.2 and are independent of the scene origin; rebasing the scene never changes
`s`, edge indices or projection results.

| Option (render scene) | Precision | Accuracy ≤ 5 km | Complexity | Verdict |
|---|---|---|---|---|
| Local ENU (tangent plane at origin on the R = 6 371 km sphere, double) | double on CPU, float offsets on GPU | tangent-plane vertical drop d²/2R: 0.03 m at 600 m, 1.96 m at 5 km (DERIVED, R = 6 371 km) → rebase before 5 km | low (closed-form) | **chosen** |
| Local equirectangular | double | scale varies with latitude (cos term) — metre-level error over several km (INFERRED) | lowest | rejected for projection maths |
| Web Mercator | double | scale factor 1/cos(lat) ≈ 1.63 at 52° (DERIVED) must be corrected everywhere | medium | used only for tile addressing |
| UTM 34N | double | < 1 m | needs projection library | rejected (no benefit over ENU) |
| PL-1992 | double | good | needs projection on device | rejected (DEM is resampled offline; no need on device) |

### 10.3 Floating origin

- Scene origin = first attached projected point of the ride; axes **X = east, Y = north, Z = up**, metres.
- Rebase when the rider is > 1 000 m (TARGET) from the origin: new origin = current projected point; all chunk transforms updated
  (Option A); the route index has no global plane at all (Strategy C, §6.2), so projection and `s` are unaffected.
- Scene ENU uses the same sphere (R = 6 371 000 m) as `GeoPoint.distanceTo`, so scene distances near the origin agree with the
  haversine axis to the §6.2.2 table precision (≈ 4 mm at 10 km, sub-mm within the 1 km rebase radius, DERIVED).
- GPU receives float32 positions relative to origin: at ≤ 1.5 km, float32 spacing ≈ 1.2 × 10⁻⁴ m (DERIVED 2⁻²³ × 1024 m) — ample.

### 10.4 Vertical origin/reference

Z = NMT normal height (EVRF2007) − origin normal height. Ellipsoidal GPS altitude is never used in the scene. Option C displays
relative metres only; absolute heights only as optional labels with "m n.p.m." semantics from NMT.

---

## 11. ElevationSampler

### 11.1 API concept

```kotlin
interface ElevationSampler {
    fun sample(lat: Double, lon: Double): ElevationSample          // suspend-free, may hit cache only
    suspend fun prefetch(bounds: GeoBounds)                        // IO dispatcher
    val metadata: ElevationSourceMetadata                          // resolution, datum, source, build id
}
sealed interface ElevationSample {
    data class Value(val heightM: Double, val confidence: Float) : ElevationSample
    data class Unavailable(val reason: UnavailableReason) : ElevationSample  // NOT_LOADED, NO_TILE, NODATA, CORRUPT, OUT_OF_COVERAGE
}
```

Implementations: `SyntheticElevationSampler` (TA-001A: plane, slope, sine hills, step, ramps of given grade/length, isolated
spike, noise with seed) and `PmtilesElevationSampler` (TA-001B).

Non-destructive contract (C2): the sampler returns what the DEM contains (interpolated per §11.2) or `Unavailable`; it never
smooths, clamps outliers or substitutes plausible values. Its outputs are written once into `RawElevationProfile` (§12.1). No
renderer, profile filter or blending step writes back into the sampler, its caches or the archive.

### 11.2 Interpolation

Bilinear over the 4 surrounding samples in Mercator pixel space. If any of the 4 is nodata → nearest valid neighbour among them
with confidence × 0.5 (TARGET); if all 4 invalid → `Unavailable(NODATA)`.

### 11.3 Tile boundaries

Samples at pixel column 255 need column 0 of the neighbour tile. The sampler reads the neighbour decoded block from cache (prefetch
loads a 3 × 3 neighbourhood around the route window). If the neighbour tile is absent → clamp to edge sample, confidence × 0.5.

### 11.4 Missing/corrupt data

- Missing tile → `Unavailable(NO_TILE)`; outside archive bounds → `OUT_OF_COVERAGE`.
- Corrupt (PNG decode failure, wrong dimensions, checksum mismatch when available) → tile marked bad for the ride, `CORRUPT`, one
  diagnostic log line, never retried in a loop.
- Nodata encoding: pipeline writes nodata pixels as a reserved RGB value outside the plausible height range (e.g. decoded ≥ 9 000 m),
  sampler maps it to nodata. **A missing sample is never 0 m.**

### 11.5 Confidence

Per sample: 1.0 = 4 valid neighbours from EVRF2007 source; reduced for neighbour substitution, edge clamping, source year older than
N years (metadata, TARGET N = 10), or KRON86 fallback (should not occur, §7.5).

---

## 12. Route elevation profile and grade

(Corrected in TA-000B-C1 / C2. The original draft replaced spikes and short bumps/dips by interpolation by default; that is
withdrawn. Nothing below rewrites DEM-derived values.)

Profile layers (semantic separation is mandatory; one layer never overwrites another):

| Layer | Content | Mutability | Consumers |
|---|---|---|---|
| `RawElevationProfile` | sampler outputs at fixed `s` positions: height or `Unavailable(reason)`, sample confidence, status flags (e.g. neighbour substitution §11.2) | immutable once a window is filled | Filtered, anomaly detector, validation |
| `ProfileAnomalies` | metadata spans (§12.4) | append-only | confidence, UI hatching, validation |
| `FilteredElevationProfile` | derived heights + confidence + raw source span per sample + `FilterConfig` id | recomputable, never written back to Raw | Grade, Display |
| `GradeProfile` | grade per position from **Filtered** only + raw source span + config id | recomputable | GradeEventDetector |
| `DisplayElevationProfile` | presentation heights (vertical scaling, optional extra visual smoothing) for the instrument/renderer | presentation only | UI/renderer; **never** feeds grade/events |

### 12.1 Sampling

Sample positions every **5 m** along the navigation-compatible axis `s` (§6.2; point at `s` = edge `i` with `t = (s − sᵢ)/hᵢ`,
interpolated in the edge-local frame) (TARGET: ≈ 1.7 DEM samples at 2.93 m spacing; 100 km → 20 001 samples, DERIVED).
Windows are filled lazily ahead of the rider (e.g. next 2 km, refilled at 1 km) so startup cost is bounded. `Unavailable` samples
are stored as such (status + NaN height internally, never 0 m) and are never interpolated across in Raw.

### 12.2 Filtering (derived, non-destructive)

`FilterConfig v1` (TARGETS, frozen for G-DATA before TA-001B runs):
1. Median over 5 samples (spans 20 m between centres, 25 m effective) — removes isolated single-sample spikes; is the identity on
   any monotonic run (DERIVED property of the median), so ramps and plateau-to-plateau climbs pass unchanged.
2. Moving average over 5 samples (25 m effective width).
3. A filtered sample whose source window contains any `Unavailable` raw sample is itself `Unavailable` (no bridging of gaps;
   gaps widen by ≤ 4 samples = 20 m per side, DERIVED).
4. Each filtered sample stores its raw source span (median window ∪ average window = raw indices [j − 4, j + 4], DERIVED).
Maximum smoothing: combined effective support never exceeds 60 m (TARGET). Any other filter is a separate named config and a
separate experiment, never silently substituted.

### 12.3 Smoothing attenuation (derived budget, measured in TA-001A)

Grade uses `g(s) = (hF(s + 12.5 m) − hF(s − 12.5 m)) / 25 m` (L = 25 m, half-sample positions linearly interpolated in Filtered).
Moving average (25 m box) followed by the L = 25 m difference is equivalent to convolving the true grade with a triangular kernel
of 50 m base (DERIVED; the median is ignored because it is the identity on the monotonic test ramps). For an isolated ramp of
grade g and length ℓ, filtered peak = g · M, where M = kernel mass inside ℓ = 1 − 2·(25 − ℓ/2)²/1250 for ℓ ≤ 50 m, M = 1 for ℓ ≥ 50 m
(DERIVED). Pre-declared TARGET budget (theory + margin for sampling/interpolation):

| Synthetic case | Raw peak | Theory filtered peak | Attenuation budget (TARGET) | Elevation-change budget | Event outcome required |
|---|---|---|---|---|---|
| +12 % over 40 m, flat around | 12 % | 11.52 % (−0.48 pp, 4 %) | ≤ 1.0 pp | ≤ 0.1 m (plateau-to-plateau) | retained as CLIMB |
| +15 % over 25 m | 15 % | 11.25 % (−3.75 pp, 25 %) | ≤ 4.5 pp | ≤ 0.1 m | retained as CLIMB (short rule) |
| +8 % over 100 m | 8 % | 8.00 % (0) | ≤ 0.5 pp | ≤ 0.1 m | retained as CLIMB |
| −12 % over 40 m | −12 % | −11.52 % | ≤ 1.0 pp | ≤ 0.1 m | retained as DESCENT |
| +10 % over 30 m then flat (short real hill) | 10 % | 8.40 % (−1.6 pp) | ≤ 2.0 pp | ≤ 0.1 m | retained as CLIMB (short rule) |
| isolated false spike (+3 m, one sample) | — | removed by median | filtered max |g| ≤ 1.0 pp | 0 m | no event; raw keeps the spike; anomaly flagged |
| step discontinuity (2 m over one sample) | — | becomes a ramp (median keeps monotone steps) | report only | report | report only; anomaly flagged |

Also reported for every case (no silent thresholds): raw/filtered peak grade, absolute (pp) and relative (%) attenuation, raw and
filtered elevation change, event boundary shift (theory ≤ ~10 m for the ramps above; TARGET ≤ 15 m), event type/class change.
TA-001A implementation must reproduce the theory column within ±0.3 pp (TARGET) — a larger gap indicates an implementation defect.
Changing `FilterConfig v1` after TA-001A requires recomputing this table and operator review before TA-001B.

### 12.4 Anomaly detection = metadata only

- `ProfileAnomalyDetector` emits spans `{startS, endS, kind, magnitudeM, detectorParams, confidence}` with kinds
  `SUSPECTED_PROFILE_ANOMALY` (e.g. dip/bump > 3 m over < 60 m returning to within 1 m of the pre-level, isolated single-sample
  deviation > 2 m from the local median, step > 1.5 m between adjacent samples — all TARGET detector parameters) and
  `LOW_CONFIDENCE_SPAN` (substituted/edge-clamped samples, old source year).
- Detection never modifies Raw, Filtered or Grade values. Effects: span confidence ↓, UI may hatch the span; events overlapping a
  suspected span carry `confidence` ↓ but are **not suppressed**.
- Shape alone never yields `BRIDGE`, `TUNNEL`, `EMBANKMENT` or `FORD` labels. `RouteSegment` carries no such attributes (C7);
  the vector PMTiles transportation layer carries `bridge`, `tunnel`, `ford` (VERIFIED `tools/tiles/process.lua:43-50`) — a
  possible future confirming source (backlog), only then may structure labels be used.
- Fords, drainage dips, cuts and embankments are real terrain for the rider and remain visible unless G-DATA proves a specific
  correction helps (§12.7).

### 12.5 Grade events

- Grade from `GradeProfile` (Filtered heights, §12.3). Never from adjacent DEM pixels, never from Display heights, never from
  renderer mesh heights.
- Candidate span: contiguous run with |g| ≥ **4 %** that ends when |g| < **2.5 %** (hysteresis) (TARGETS).
- The span is an **event** if either (TARGETS, pre-declared in C1):
  - **standard**: span length ≥ **50 m**, or
  - **short-steep**: the span contains a sub-run with |g| ≥ **7 %** of length ≥ **10 m**.
  (The short-steep rule is DERIVED from §12.3: +10 %/30 m filters to a ≥ 7 % sub-run ≈ 16 m wide and a 4 % span ≈ 35 m long,
  which the standard rule alone would drop; a 2 m step yields a ≥ 7 % run of only ≈ 6 m and is not an event.)
- Event model: `type ∈ {CLIMB, DESCENT}`, `class ∈ {STANDARD, SHORT}` (SHORT if length < 50 m), `startDistanceM`, `endDistanceM`,
  `lengthM`, `averageGrade`, `maxGrade` (signed, max |g|), `elevationChangeM` (Filtered end − start), `confidence`,
  `rawSpan`, `configId`.
- Outputs to presentation: `currentGrade`, next CLIMB / next DESCENT events.
- Missing data: a span containing `Unavailable` grade samples is split; the unavailable part emits no event and shows "no data",
  never "flat".
- TA-001B reports distributions (|Δh| over 300/600 m, grade histograms) for the validation routes (resolves U2). These inform the
  **product** UX thresholds in TA-007; they do **not** retune the G-DATA detector (§22.3). Any detector change after G-DATA is a
  new declared experiment and requires re-running G-DATA.

### 12.6 Confidence

Profile span confidence = min of raw sample confidences in the grade window's raw source span × anomaly factor (0.5 inside a
suspected span, TARGET). Separate from surface confidence (`RoadDataConfidence`, VERIFIED `RouteSegment.kt:17`).

### 12.7 Correction heuristics are candidates, not defaults

TA-001B compares on real data RAW, FILTERED (`FilterConfig v1`), ANOMALY-FLAGGED and an OPTIONALLY-CORRECTED candidate (e.g.
interpolating across suspected spans) against the 1 m reference. A destructive correction becomes production behaviour only if
it improves G-DATA-2/3 **and** does not reduce G-DATA-4 feature retention (§22.3). Otherwise: detect + flag + do not rewrite.

### 12.8 Traceability memory cost (DERIVED)

Per sample: Raw = s, lat, lon, height (4 × 8 B) + status (1 B) + confidence (4 B) = 37 B; Filtered = height (8) + confidence (4)
+ raw span (2 × 4) = 20 B; Grade = 20 B (same layout); Display (full route, Float) = 4 B → ≈ 81 B/sample.
100 km at 5 m = 20 001 samples ≈ **1.6 MB**; 300 km ≈ **4.9 MB**; anomaly spans and events (hundreds × ~64 B) < 50 KB.
With lazy 2 km windows the live footprint is ≈ 32 KB. Traceability is kept; saving this memory is not a reason to drop layers.

---

## 13. Renderer architecture

(Applies to Option A. Option C uses Compose Canvas — already on the classpath, VERIFIED `designsystem/build.gradle.kts`.)

### 13.1 Why MapLibre Native is/is not sufficient

Not sufficient for true 3D: no terrain on Android (AUDIT ML-5/ML-6), pitch ≤ 60° (AUDIT ML-2), DEM int-metre quantisation (C10),
CustomLayer requires native code (AUDIT ML-4).

**Does a separate MapLibre-Native raster-dem/PMTiles POC reduce meaningful risk? NO.** The terrain pipeline's correctness is proven
by the Kotlin sampler (TA-001B), and archive well-formedness by the reference PMTiles tooling. A MapLibre-Native POC would only
prove hillshade in MAP, which is not on the Terrain Ahead path. It is removed from the roadmap; "hillshade in MAP" becomes optional
backlog (§27).

### 13.2 Filament

VERIFIED upstream (2026-09-24): `google/filament`, Apache-2.0, latest `v1.77.1` (2026-09-21), active; Android artifact
`com.google.android.filament:filament-android:1.77.1`; backends OpenGL ES 3.0+ and Vulkan 1.0 on Android; Android `minSdk 21`
(≤ app 26 ✓) but built with `compileSdk 37` (app uses 34 → U7); `UiHelper` for `SurfaceView`/`TextureView` integration (README).
Materials require offline `matc` compilation or the large runtime `filamat` library (README).

Risks: native ABI size (UNKNOWN, measure in TA-002), material build step in Gradle, manual lifecycle (engine/renderer/swapchain
destroy), Compose embedding via `AndroidView` + `SurfaceView`, dynamic `VertexBuffer`/`IndexBuffer` replacement per chunk (supported
API concept, to prove in spike), debugging via Filament tools.

### 13.3 SceneView / maintained Filament wrapper

VERIFIED: repository moved to `sceneview/sceneview` (Apache-2.0), very active (v4.37.0 → v4.39.0 between 2026-09-16 and 2026-09-22);
Android = Filament + Jetpack Compose, status "Stable"; custom geometry via `GeometryNode`/`MeshNode` with direct Filament buffers;
pins **Filament 1.72.1** (`gradle/libs.versions.toml`) while upstream is 1.77.1; scope spans iOS/Web/Desktop/AR.

Assessment: reduces Compose/lifecycle boilerplate, but couples Filament version and release churn to a fast-moving multi-platform
wrapper, for a scene of a few dynamic meshes. **Not selected for the spike**; revisit only if the Filament spike's lifecycle code
proves disproportionate.

### 13.4 MapLibre GL JS in WebView

VERIFIED: `maplibre/maplibre-gl-js` BSD-3 licence, latest v6.11.2 (2026-09-24); style-spec terrain support in GL JS since 2.2.0
(AUDIT ML-5); PMTiles JS `Protocol` + pluggable `Source` interface with `getBytes(offset, length)` (VERIFIED `js/src/index.ts`
lines 295-339) → a custom Source can read a local archive through a bridge instead of HTTP.

Specific risks: local random access (WebView asset loader range support UNKNOWN, U8 — the custom Source avoids reliance on it but a
`@JavascriptInterface` returns strings → base64 overhead, INFERRED); WebView GPU composition and extra process memory; startup
latency; position updates at 1 Hz from Kotlin via `evaluateJavascript` (fine at 1 Hz; smoothing must run in JS); background
suspension and lifecycle; dependency on the device's updatable WebView version; pitch limit of GL JS (to be checked in spike).
Strength: terrain, raster-dem, route line styling and camera APIs already exist.

### 13.5 Raw OpenGL ES

Full control, no dependency, but lighting, materials, text/markers, lifecycle and debugging are all custom — highest code volume
for the same scene. Rejected for the spike; remains the fallback if both candidates fail on size/licence grounds.

### 13.6 Raw Vulkan assessment

Not justified: the scene is a few thousand-triangle meshes; Vulkan adds large complexity and driver variance on mid-range Android
without a measurable benefit here. Filament already offers a Vulkan backend if ever needed.

### 13.7 Decision matrix

| Criterion | Filament | SceneView | GL JS/WebView | Raw GLES | Vulkan |
|---|---|---|---|---|---|
| Custom dynamic meshes | 5 | 4 | 3 (via terrain + custom layers) | 5 | 5 |
| Terrain from DEM out of the box | 1 | 1 | 5 | 1 | 1 |
| Offline data path | 5 (Kotlin sampler) | 5 | 3 (U8) | 5 | 5 |
| Lifecycle/Compose integration | 3 | 5 | 3 | 2 | 1 |
| Dependency risk | 4 (Google, active) | 2 (churn, pinned Filament) | 3 (WebView variance) | 5 | 5 |
| Code volume | 3 | 4 | 4 | 1 | 1 |
| Battery/thermal (expected) | 4 | 4 | 2 | 4 | 4 |

### 13.8 Recommendation

```text
RECOMMENDATION  If Option A proceeds: spike Filament (primary) and MapLibre GL JS/WebView (challenger) on the identical scene;
                choose by §21 metrics and the G-3D glance test.
WHY             Filament: mature, Apache-2.0, native Android, dynamic buffers, reuses the Kotlin terrain core.
                GL JS: fastest path to terrain + route styling; consumes the same PMTiles Terrain-RGB archive.
REJECTED        SceneView (version coupling/churn), raw GLES (code volume), Vulkan (unjustified), MapLibre Native (no 3D).
INVALIDATION    Filament fails AAR/compileSdk compatibility (U7) without an acceptable upgrade path, or exceeds APK/RAM budgets;
                GL JS cannot meet 30 fps p95 or local-archive access on mid-range.
```

### 13.9 Renderer spike design (TA-002)

Identical scene for every candidate (§35 requirement):
- DEM: one fixed TA-001B z15 test archive (≈ 3 × 3 km).
- Route: fixed 3 km route from TA-001B (curve, climb, descent, junction marker, 3 surface classes).
- Terrain extent: 600 m ahead, 100 m behind, ±250 m lateral (TARGET).
- Road ribbon + active route line; rider marker.
- Camera path: scripted replay at 40 km/h with 1 Hz position input + interpolation (§15).
- Measurements: §21.5 procedure on one mid-range and one flagship device class.
Spike code lives on a separate branch/module never merged into `main` without a follow-up task.

---

## 14. Scene geometry (Option A; C uses §3.3 only)

### 14.1 Terrain extent

600 m ahead, 100 m behind, ±250 m lateral (TARGETS; product 300–600 m look-ahead plus margins for turning). Aligned to the route
tangent, realised as the set of render chunks intersecting that oriented rectangle.

### 14.2 Terrain chunks

256 m ENU squares, 64 × 64 quads (DERIVED §9.5); for the extent above ≈ 3 × 3 to 4 × 3 chunks visible (DERIVED 700 m / 256 m ≈ 3,
500 m / 256 m ≈ 2 → with alignment slack 12 chunks max, TARGET cap). Built on a background dispatcher, uploaded on the render thread.

### 14.3 LOD

Not needed in V1: 12 × 8 192 ≈ 98 k triangles (DERIVED) is modest (INFERRED). Optional: far chunks at 8 m spacing (2 048 triangles).

### 14.4 Road ribbon

Centerline = `RouteSegment.points` (VERIFIED). Height = `DisplayElevationProfile` (§12), **not** raw DEM samples. Width by functional class from
`highway` (TARGET semantic widths: track/path 3 m, service/unclassified 4 m, tertiary+ 6 m — visual only). Colour/pattern by
`surface` band only (no `smoothness`, `osmWayId`, `name`, `access` — C7). Ribbon lifted 0.15 m (TARGET) to avoid z-fighting.

### 14.5 Terrain-to-road blending

Recommended, visualisation-only: within 6 m (TARGET) of the centerline set terrain vertex height to road height; blend smoothly to
unmodified terrain at 20 m (TARGET), max adjustment 3 m (TARGET); beyond that, leave the conflict visible and mark low confidence.
Suspected profile anomaly spans (§12.4 — not asserted to be bridges/tunnels): no blending; ribbon follows the Display profile.
This falsifies terrain locally by design. Grade safety rule (C2): blended heights live only in GPU/CPU mesh buffers and **never
feed** `ElevationSampler`, `RawElevationProfile`, `FilteredElevationProfile`, `GradeProfile` or `GradeEventDetector`; the ribbon
height itself comes from `DisplayElevationProfile`, which is also presentation-only.

### 14.6 Active route

Separate line layer above the ribbon, constant screen-space width, high luminance contrast against all surface colours (TARGET:
≥ 3:1 contrast vs every ribbon colour, measured on the palette in TA-004), dashed or outlined so the road shape stays visible; drawn
with depth-test offset so it is not hidden at crests.

---

## 15. Camera

### 15.1 Route-following pose

Option A: camera target = interpolated projected point + 60 m ahead along route (TARGET); camera behind at 40 m, height 25 m,
pitch ~35° (TARGETS, tuned in spike). Option C: the strip's "camera" is the window [s − 50, s + 600] (TARGET).

### 15.2 Position interpolation

All camera progress values are on the navigation-compatible axis `s` (§6.2); a pose at `s` is resolved to edge `i`, `t = (s − sᵢ)/hᵢ`,
position interpolated in the edge-local frame, then converted to render-scene ENU (§10) — scene rebasing never changes `s`.
`sDisplay(t)` moves toward `sTarget` with bounded speed: `sDisplay += clamp(sTarget + v·(t − tFix) − sDisplay, −1 m, v·dt·1.3)`
(TARGET constants): conservative prediction up to 1.0 s past the last fix, never beyond `sTarget + 15 m` (TARGET). Interpolation
runs in the view (render thread for A, animation clock for C), not in `:terrain`.

### 15.3 Heading

ATTACHED: route tangent at `sDisplay` (edge-local vector (dx, dy), §6.2.3) smoothed over 30 m of route (TARGET). DETACHED: `NavigationState.currentBearing` (held at low
speed by navigation, C2) with 0.5 s exponential smoothing (TARGET).

### 15.4 Low speed

`currentSpeedMps == 0` (navigation reports < 1.5 m/s as 0, C2): stop prediction, keep last pose, no heading change unless attached
tangent changes.

### 15.5 GPS loss

No fix timestamp exists in `NavigationState` (C3) and stationary riders may produce no emissions (C4). Rule: if no emission for
3 s (TARGET) **and** last speed > 0 → "position stale" after prediction limit: freeze pose, show stale indicator after 5 s (TARGET);
if last speed == 0 → treat as stationary (no indicator). Both are harmless freezes; no dead-reckoning beyond 1 s.

### 15.6 Off-route / GPX

Off-route: DETACHED mode — Option C shows "poza trasą" state with distance-to-route (`distanceToGpxMeters` for GPX when provided,
VERIFIED `NavigationState.kt:22`); Option A centres on GPS position with GPS heading and keeps the route visible. GPX: same as
calculated routes; gaps render as broken ribbon; no maneuvers (VERIFIED `GpxRoute.kt`: none created).

---

## 16. Runtime state model

### 16.1 State matrix

| State | Reachability | Terrain data | Projection | Visual output (C / A) | Fallback | Rider sees | Nav impact |
|---|---|---|---|---|---|---|---|
| ON_ROUTE | REACHABLE | window ahead | ATTACHED | profile + bands + marker / 3D follow | — | normal | none |
| OFF_ROUTE | REACHABLE | keep last window | DETACHED | "poza trasą" + last profile greyed / GPS-centred | — | off-route banner (navigation's own UI unchanged) | none |
| RECALCULATING | UNWIRED (AUDIT F4) | keep | DETACHED | "przeliczanie" greyed | — | greyed | none |
| ROUTE_RECOVERED | REACHABLE (`OffRouteDetector`) | keep | re-attach §6.3 | normal after re-attach | — | normal | none |
| FOLLOWING_GPX | REACHABLE | window ahead | ATTACHED (gap-aware) | profile; surface bands "nieznana" (C7) | — | normal, surface unknown | none |
| RECOVERED | UNWIRED | build on first emission | init without `sPrev` | normal | — | normal | none |
| ARRIVED | REACHABLE | none needed | HOLD at end | "cel" end state | — | finish | none |
| IDLE | REACHABLE (after stop) | release | NO_ROUTE | TERRAIN hidden | MAP | MAP | none |
| route changed (new `route.id`) | DEFENSIVE (only via unwired `reroute`) | invalidate + rebuild | reset | "ładowanie" ≤ 1 s | — | brief loading | none |
| route removed | REACHABLE (`stopNavigation`) | release | NO_ROUTE | TERRAIN hidden | MAP | MAP/post-ride | none |
| free ride / no route | UNWIRED (C13) | optional ring around GPS (A) / none (C) | NO_ROUTE | "brak trasy" | MAP | MAP | none |
| GPS lost | REACHABLE | keep | unchanged | freeze + stale indicator §15.5 | — | stale indicator | none |
| DEM tile missing | REACHABLE (coverage gaps) | `Unavailable` span | unaffected | hatched "brak danych" span / flat grey patch | surface bands + marker continue | partial data | none |
| DEM tile corrupt | DEFENSIVE | tile rejected | unaffected | as missing + diagnostic | same | partial data | none |
| map PMTiles missing | REACHABLE (AUDIT R12) | independent | unaffected | TERRAIN works if terrain archive exists | TERRAIN is a valid alternative view | terrain instrument | none |
| renderer init failure | DEFENSIVE | — | — | TERRAIN tab disabled with reason | MAP | MAP + disabled TEREN | none |
| renderer runtime failure | DEFENSIVE | release | — | auto-switch to MAP once, tab disabled for the ride | MAP | MAP | none |

### 16.2 Reachable vs unwired states

Reachability labels follow AUDIT F4 (unwired `reroute`, `restoreSession`, `saveNavigationSession`) and C13 (no route-less RIDING).
Terrain handles unwired states defensively only; it does not wire them (§27).

### 16.3 Failure behaviour

- Terrain runs in its own coroutine scope with a `SupervisorJob` and a `CoroutineExceptionHandler`; any exception → terrain state
  `Failed(reason)`, MAP forced visible, navigation untouched (INFERRED design rule).
- No terrain code runs inside `NavigationManager`'s scope or collectors.
- Terrain's view never depends on the map style being ready — explicitly avoiding AUDIT R12's pattern where overlays depend on
  `styleReady`.

---

## 17. Threading and lifecycle

### 17.1 Thread ownership

| Work | Thread | Scope |
|---|---|---|
| `NavigationState` collection | `Dispatchers.Default` collector | view-scoped (TERRAIN visible) |
| Route index build | `Default` | ride-scoped (per `route.id`) |
| Projection | `Default`, in collector | view-scoped |
| Archive open / tile IO | `Dispatchers.IO` (limited parallelism 2, TARGET) | ride-scoped |
| PNG decode | `Default` (limited parallelism 2) | ride-scoped |
| ElevationSampler (cache reads) | caller thread, lock-free read of immutable blocks | ride-scoped |
| Profile + grade | `Default` | ride-scoped |
| Mesh generation (A) | `Default` | view-scoped |
| GPU upload + render loop (A) | renderer thread (Filament/WebView) | render-thread-scoped |
| Canvas draw (C) | main thread, draw only (no allocation-heavy work) | view-scoped |
| Cache eviction | `Default`, on progress events | ride-scoped |

### 17.2 Cancellation

| Event | Action |
|---|---|
| route change | cancel profile/prefetch jobs for old `route.id`; drop mesh cache; keep decoded tiles (may be reused) |
| ride end (`stopNavigation` → IDLE) | cancel ride scope; close archive; clear all caches |
| MAP → TERRAIN | start view scope; resume renderer; MapLibre `onPause` (§19) |
| TERRAIN → MAP | cancel view scope; pause/destroy renderer per §19; keep ride-scoped data |
| Activity destroy | Compose disposal cancels view scope; ride scope owned by a ride-scoped holder in `:app` is cancelled on RIDING exit |
| process shutdown | no persistence required (terrain is derivable); nothing to flush |

### 17.3 Component lifetimes

- **app-scoped**: none in V1 (avoid growing `MazoviaOffroadApp`; keeps it unchanged).
- **ride-scoped**: archive handle, decoded tile cache, route index, profile — held by a `TerrainRideSession` created when RIDING
  composes with a non-null route, released on IDLE/route removal.
- **view-scoped**: projection collector, presentation flow, interpolation, mesh cache.
- **render-thread-scoped**: GPU buffers, Filament engine/scene or WebView.

---

## 18. Cache architecture

### 18.1 Disk

Terrain archive(s) in the pack directory (§20). Read-only, memory-mapped or `FileChannel` positional reads; no disk cache of decoded data.

### 18.2 Decoded DEM

LRU of decoded blocks, cap **16 MB** (TARGET) = 64 blocks × 256 KB (DERIVED). Keep: tiles intersecting [s − 200 m, s + 2 km] corridor
(TARGET). Prefetch direction: ahead along route. Evict behind first.

### 18.3 CPU mesh

Option A: ≤ 24 chunk meshes × ~150 KB ≈ 3.6 MB (DERIVED), cap 8 MB (TARGET). Keep chunks within [s − 150 m, s + 800 m].

### 18.4 GPU resources

Option A: ≤ 16 chunks resident (TARGET) ≈ 2.4 MB geometry (DERIVED) + textures (none in V1 beyond small palettes); cap 16 MB (TARGET).

### 18.5 Eviction/preload

All caches keyed by distance-along windows, so memory is bounded independent of ride length. Route change invalidates route-keyed
caches (mesh, profile); decoded tiles are geography-keyed and survive route changes.

---

## 19. MAP ↔ TERRAIN integration

### 19.1 Renderer ownership options

| Option | RAM/GPU | Battery | Switch latency | Complexity | Failure recovery |
|---|---|---|---|---|---|
| A both alive and rendering | highest | worst | instant | low | good |
| B MapLibre allocated, paused (`onPause`/`onStop`) while TERRAIN visible | medium | good | fast (resume) | medium: must keep MapView in composition while hidden | good |
| C destroy/recreate on switch | lowest | good | slow (style reload, PMTiles open) | low | good |

Current container forwards Activity lifecycle and calls `onDestroy()` in `onDispose` (VERIFIED `MapLibrePMTilesPOCContainer.kt:347-365`);
so simply removing the MapView from composition = Option C behaviour today.

### 19.2 Recommendation

```text
RECOMMENDATION  Option C (V1 = instrument): keep MapView alive and visible — the instrument replaces only part of the map box or
                overlays it; no pausing needed; negligible extra cost.
                Option A (3D): Option B — keep MapView composed but hidden, call MapView.onPause() (renderer pause, VERIFIED C11)
                while TERRAIN is shown, onResume() on return.
WHY             fastest safe switch; MapLibre 11.11.0 exposes the needed calls; avoids style reload on every switch.
REJECTED        A (two GPU renderers at once); C for 3D (slow switches, repeated native init).
INVALIDATION    RAM pressure on mid-range with both allocated (> §21.3 cap) → fall back to C (destroy) for 3D.
```

Note: pausing MapLibre from Compose requires the MAP container to accept a "paused" parameter; that is a MODIFIED file (§24) but not
a frozen class.

### 19.3 Navigation continuity

`NavigationManager` is app-scoped and unaffected by view switches (VERIFIED `MazoviaOffroadApp.kt:82`); maneuver card, status pills,
data panel and action buttons stay outside the switched box (VERIFIED layout `RidingScreen.kt:153-410`).

### 19.4 Failure fallback

TERRAIN failure → MAP shown, TEREN tab disabled with reason for the rest of the ride; MAP never depends on terrain objects.

---

## 20. Offline package architecture

### 20.1 Current reality

Graph at `filesDir/graph` via SAF copy + validated swap; map PMTiles side-loaded at `getExternalFilesDir(null)/mazowieckie_offroad.pmtiles`;
`RidePackEvaluator` checks existence/readiness only (AUDIT F5, VERIFIED `RidePackEvaluator.kt:30`).

### 20.2 Proposed Ride Pack evolution

```text
<externalFilesDir>/ride-packs/<packId>/
    manifest.json
    map/       *.pmtiles        (vector map, optional per pack)
    terrain/   *.pmtiles        (Terrain-RGB, corridor or area)
    routing/   (reference to graph; graph stays at filesDir/graph until a routing task moves it)
```

Terrain is the **first** component to use the pack layout; map and routing join later without changing their current loaders now.

### 20.3 Manifest

`formatVersion`, `packId`, `createdAt`, `minAppVersion`, `coverage` (bbox + optional corridor polygon/route id), per component:
`type` (map|terrain|routing), `path`, `sha256`, `sizeBytes`, `sourceDatasets` (name, version/year, licence, attribution),
`crs`, `tileScheme`, `zoom`, `encoding`, `verticalDatum` ("PL-EVRF2007-NH"), `nodata`, `buildManifestRef` (pipeline manifest id).

### 20.4 Terrain component

One or more Terrain-RGB PMTiles per pack; the sampler composes multiple archives by coverage; checksum verified at install time,
not on every open (TARGET: open cost ≤ 50 ms).

### 20.5 Migration

1. TA-001B/TA-008: terrain archives install into the pack layout; the app also accepts a loose `terrain/*.pmtiles` next to the
   existing map file during development.
2. Existing map file keeps working at its current path (no change to `MapLibrePMTilesPOCContainer`, VERIFIED path).
3. A later, separate task may move map/graph into packs with a compatibility lookup (old path first).

### 20.6 Readiness implications

`RidePackEvaluator` is **not modified** (it gates departure via `hasEssentialDepartureBlocker`, VERIFIED `RidePackReadiness.kt`).
A new `TerrainReadinessEvaluator` in `:terrain` returns READY / PARTIAL (coverage < 100 % of route) / NOT_READY / NOT_REQUIRED and is
shown next to the existing readiness card in `:app`. Terrain never blocks departure. No frozen-class exception needed.
Alternative (OD-7): integrate into `RidePackEvaluator` later if the operator wants one combined verdict.

---

## 21. Performance budgets

### 21.1 Mid-range target

Device class (TARGET): Android 12+, 4–6 GB RAM, 2021–2023 mid-range SoC with OpenGL ES 3.2 GPU, 1080p screen.

| Metric | Option C | Option A |
|---|---|---|
| FPS | 30 while animating (idle otherwise) | ≥ 30 sustained |
| Frame time p95 | ≤ 8 ms draw on main thread | ≤ 33 ms |
| Tile lookup + decode (z15 PNG) | ≤ 15 ms p95, off main thread | same |
| Profile window (2 km) build | ≤ 50 ms | same |
| Chunk mesh build | — | ≤ 8 ms per chunk, background |
| GPU upload per chunk | — | ≤ 2 ms |
| MAP → TERRAIN | ≤ 300 ms | ≤ 500 ms warm, ≤ 1 500 ms cold |
| TERRAIN → MAP | ≤ 300 ms | ≤ 300 ms (resume) |
| Cold terrain start (archive open + first window) | ≤ 500 ms | ≤ 1 500 ms |

### 21.2 Flagship target

Device class (TARGET): current-generation flagship SoC, 8+ GB RAM. Option A: 60 fps, p95 ≤ 16.7 ms; other limits halved (TARGET).

### 21.3 Memory/storage targets

| Item | TARGET |
|---|---|
| Terrain subsystem heap + native (C) | ≤ 32 MB |
| Terrain subsystem incl. renderer (A) | ≤ 96 MB (+ renderer baseline measured in TA-002) |
| Decoded DEM cache | 16 MB (DERIVED 64 blocks) |
| CPU mesh cache (A) | 8 MB |
| GPU geometry (A) | 16 MB |
| APK/AAB delta (A) | ≤ 8 MB per ABI split (UNKNOWN until TA-002) |
| Terrain storage per 100 km route pack | ≤ 50 MB (vs DERIVED 17.5–46.7 MB estimated compressed z15, §9.4) |

### 21.4 Battery/thermal targets

- Option C: ≤ +3 % battery vs MAP over 30 min (TARGET; MAP stays rendering).
- Option A: ≤ +15 % vs MAP over 30 min; no thermal-throttling-induced drop below 30 fps within 30 min at room temperature (TARGET).

### 21.5 Measurement plan

`adb shell dumpsys gfxinfo <pkg> framestats` (frame times), `dumpsys meminfo` (RAM), Android Studio profiler / Perfetto traces
(decode, mesh, upload spans with `Trace.beginSection`), `dumpsys thermalservice` sampling every 10 s. Each task that owns a metric
records results in its result document; values then become MEASURED.

Battery protocol (TA-009; clarification in TA-000B-C1, §21.4 TARGETS unchanged):
- Primary signal: `BatteryManager.getLongProperty(BATTERY_PROPERTY_CHARGE_COUNTER)` (µAh) sampled at start/end and every 60 s,
  plus `dumpsys batterystats` / power telemetry where the device exposes it. UI battery % is too coarse (1 % steps) for a
  +3 % / 30 min budget and is recorded only as a sanity check.
- Controlled conditions: same physical device and battery health, same brightness, orientation and airplane mode, same scripted
  30-min route replay (simulator §22.2), same app state, start only when battery temperature is within the same ±2 °C band
  (TARGET) and charge within the same 60–90 % window (TARGET), device unplugged.
- Trials: ≥ 3 MAP and ≥ 3 TERRAIN runs, interleaved (MAP, TERRAIN, MAP, …).
- Report: µAh consumed per run, normalised µAh/h (and mWh/h if voltage is available), mean and range/standard deviation per mode,
  and the TERRAIN − MAP delta relative to MAP mean. A delta within the MAP run-to-run spread is reported as "not distinguishable".
- Fallback if the charge counter is absent or quantised on the reference device: longer runs (≥ 60 min) with battery % and
  `batterystats` estimated power, documented as lower-confidence; or a second reference device with a working counter.

---

## 22. Testing and validation

### 22.1 Unit tests (JVM, `:terrain`)

- Projection: §6.6 list.
- Sampler: known flat tile, synthetic 5 % slope, tile boundary continuity (|Δ| < 1 cm across edge), missing tile, corrupt tile,
  nodata, bilinear exactness at pixel centres.
- PMTiles reader: header parsing, root + leaf directory lookup, run-length entries, gzip internal compression, tile-not-found,
  truncated file → error, against archives from the reference writer.
- Terrain-RGB decode: encode/decode round trip for −100…2 000 m in 0.1 m steps.
- Profiles (C2): Raw is byte-identical before and after filtering/anomaly detection (immutability test); Filtered/Grade samples carry
  the correct raw source span and `configId`; `Unavailable` never becomes 0 m and is never bridged; determinism of `FilterConfig v1`.
- Smoothing attenuation: every row of the §12.3 table, reporting all listed quantities; theory reproduced within ±0.3 pp; required
  event outcomes met.
- Grade/events: flat (no events), constant +10 % / −10 % (one event each), isolated spike (no event; raw keeps it; anomaly flagged),
  400 m climb (one STANDARD event), long descent, short-steep cases (SHORT events), missing-sample span (split, no event, "no data").
- Event matcher (C3, §22.4): identical lists → precision = recall = 1; one runtime event overlapping two references → at most one TP;
  IoU/start-error boundary cases (IoU exactly 0.60, start error exactly 25 m → match; 0.59 / 25.1 m → no match); tie-break by
  start error; BORDERLINE classification examples; NO_EVENT_CASE; unscorable (Unavailable) spans excluded and counted.

### 22.2 Deterministic simulation

A debug-only `NavigationStateReplayer` in `:app` debug sources feeds a scripted sequence of `NavigationState`s (position, bearing,
speed, `status`, route replacement, gaps) into the terrain presenter only — it never calls `NavigationManager` (frozen). Scripts:
straight, curve, junction, hill, descent, mixed surfaces, GPX gap, GPS loss, missing DEM tile. No motorcycle needed.

### 22.3 G-DATA definitions and DEM validation (G-DATA-1)

G-DATA (after TA-001B) = G-DATA-1 AND G-DATA-2 AND G-DATA-3 (PASS / FAIL / INCONCLUSIVE) plus G-DATA-4 (PASS / REVIEW).
All metric definitions, thresholds, `FilterConfig v1` and detector parameters below are **frozen before TA-001B** (TA-000B-C1);
changing any of them afterwards is a new declared experiment that re-runs the whole gate.

Common definitions:
- **Reference DEM**: the GUGiK NMT 1 m EVRF2007 sheets used as pipeline input, sampled bilinearly in their native CRS at the query
  location (query lat/lon transformed to the sheet CRS offline with the pipeline's recorded PROJ/GDAL version).
- **Runtime DEM**: `PmtilesElevationSampler` on the built archive (z15 Terrain-RGB) at the same lat/lon.
- **Validation routes**: ≥ 5 real routes (TARGET), fixed and listed before the run, plus optional hillier corridors added only by
  operator decision (§22.4 G-DATA-3).
- **Profiles**: both sides sampled at identical `s` positions (5 m, §12.1) along the same route, same `FilterConfig v1`, same grade
  window, same `GradeEventDetector` and thresholds. Positions where either side is `Unavailable` are UNSCORABLE, excluded and counted.

**G-DATA-1 — DEM accuracy.**

- Population: 10 000 points (TARGET) uniformly random within the archive coverage, excluding nodata on either side; additionally
  reported stratified by land cover (forest vs open) and by source sheet year.
- Conditions: archive built exactly per §7.7 (EPSG:3857, z15, bilinear/average resampling as recorded in `build_manifest.json`).
- Metric: |runtime − reference| in metres.
- PASS: median ≤ 0.3 m AND p95 ≤ 1.0 m (TARGETS: 2.9 m resampling of smooth low-relief ground + ±0.05 m Terrain-RGB quantisation).
  z14 variant reported for comparison only.

### 22.4 Grade, event and feature validation — G-DATA-2 / 3 / 4

**G-DATA-2 — grade accuracy.** At every scorable 5 m position of every validation route: |g_runtime − g_reference| in percentage
points, both computed from Filtered profiles with `FilterConfig v1` and L = 25 m. PASS: p95 ≤ 1.5 pp (TARGET), reported per route
and pooled.

**G-DATA-3 — event detection** (reference = detector on reference-DEM Filtered profile; runtime = same detector on runtime-DEM
Filtered profile; event model §12.5).
- *Match*: runtime X matches reference R iff same `type` AND IoU(X, R) = overlap / union of the [start, end] intervals ≥ **0.60**
  AND |X.start − R.start| ≤ **25 m** (TARGETS). Strictness is accepted deliberately: a 50 m event shifted 20 m has IoU = 30/70 ≈ 0.43
  and does not match; the §12.3 theory predicts boundary shifts ≤ ~10 m between two equally filtered profiles, so larger shifts
  indicate real data loss.
- *One-to-one assignment*: sort all admissible (X, R) pairs by IoU descending, then |start error| ascending, then R.start ascending
  (deterministic); greedily accept a pair if neither X nor R is already assigned.
- *BORDERLINE (gate scoring only; production detector unchanged)*: a reference event R is **eligible** iff it is robust under
  either rule: (standard-robust) length ≥ 60 m AND max|g| ≥ 4.5 %, or (short-robust) it contains a sub-run with |g| ≥ 7.5 % of
  length ≥ 12 m (thresholds + 0.5 pp; minimum lengths × 1.2 — TARGETS). Non-eligible reference events are BORDERLINE.
  Pairs involving a BORDERLINE R are excluded from TP and reported as BORDERLINE_MATCHED.
  An unassigned runtime event X is BORDERLINE (not FP) iff the reference Filtered max|g| over X's interval is ≥ 3.5 % and no
  eligible reference event overlaps X; otherwise it is FP. An unassigned eligible R is FN.
- *Counts*: TP = accepted pairs with eligible R; FN = unassigned eligible R; FP = unassigned runtime events not BORDERLINE;
  precision = TP / (TP + FP); recall = TP / (TP + FN); F1 = 2PR / (P + R) reported as secondary only.
- *PASS*: pooled (micro) precision ≥ **0.90** AND recall ≥ **0.90**, with ≥ **50** eligible reference events over the dataset.
  Fewer than 50 eligible reference events (including after BORDERLINE exclusion) → **INCONCLUSIVE** (not PASS, not FAIL); the
  operator then chooses: add routes, add hillier validation corridors, reduce reliance on the event gate, or review thresholds as a
  new declared experiment. The minimum is not lowered after results are known.
- *NO_EVENT_CASE*: a route with zero eligible reference events and zero non-BORDERLINE runtime events is reported as
  NO_EVENT_CASE and contributes nothing to precision/recall. A route with zero eligible reference events but runtime FPs counts
  those FPs; its recall is N/A.
- *Per route*: eligible reference events, runtime events, TP, FP, FN, precision, recall, BORDERLINE counts, UNSCORABLE length,
  NO_EVENT_CASE. Any route with precision < 0.70 or recall < 0.70 (TARGETS) is flagged ROUTE_REQUIRES_INVESTIGATION; this alone
  does not fail G-DATA when the pooled gate passes, but its cause must be explained before proceeding.
- *Magnitude diagnostics per matched pair*: start error (m), end error (m), length error (m and %), average-grade error (pp),
  max-grade error (pp), elevation-change error (m), IoU — reported as distributions (median, p95), no separate PASS thresholds in V1.

**G-DATA-4 — terrain-feature preservation** (guards against "both sides equally over-smoothed").
- Feature reference: the same detector run on the reference 1 m DEM **Raw** profile at 5 m positions with only the L = 25 m grade
  window (no median, no moving average).
- Measured against the runtime **product** pipeline (runtime DEM + `FilterConfig v1`), same matching rule as G-DATA-3.
- Reported: feature recall, per matched feature peak-grade attenuation (pp and %), elevation-change attenuation (m), boundary shift,
  retained/lost, split by class (SHORT / STANDARD); plus the §12.3 synthetic table re-run on the final code.
- PASS: feature recall ≥ 0.80 AND median peak-grade attenuation ≤ 2.0 pp AND no SHORT-class feature with raw max|g| ≥ 12 % lost
  (TARGETS). Otherwise **REVIEW**: the operator decides whether to change `FilterConfig` (new declared experiment, full re-run) or
  accept the loss explicitly. G-DATA cannot be declared passed while G-DATA-4 is in REVIEW without that operator decision.

Only if G-DATA leaves unexplained doubt: TA-001C sensor comparison (barometer relative height, IMU pitch after mount calibration)
with GPS altitude used only as a weak cross-check (ellipsoidal datum, §7.5).

### 22.5 Renderer validation

Option C: screenshot tests of the instrument for scripted states. Option A: visual check that ribbon never intersects terrain in the
test scene, no gaps at chunk seams, active route visible in all states.

### 22.6 One-second glance test

1. 12 frames per candidate (same snippets), randomised order, on the target phone in a handlebar mount at arm's length.
2. Show 1.0 s, then blank.
3. Ask: turn direction (L/R/straight)? climb/flat/descent? junction yes/no? surface change yes/no?
4. ≥ 5 riders (TARGET), record per-question correctness and response time.
5. Pass: ≥ 80 % correct per question (TARGET); candidate comparison per §3.5.
6. Interpretation (TA-000B-C1): n = 5 is an **exploratory** usability test. The 10 pp A-vs-C margin in G-3D is a product gate
   TARGET, not statistical proof, and no statistical significance is claimed. If A and C differ by less than the margin, or results
   conflict across questions, the result is **INCONCLUSIVE**; the operator may test more riders, inspect route-type subgroups,
   keep C for V1 and defer A, or take a product decision.

### 22.7 Sunlight test

Same protocol outdoors in direct sun (midday, clear sky), max brightness, with and without polarised sunglasses; pass ≥ 70 % correct
(TARGET) and no question below 60 %.

---

## 23. Implementation roadmap

Branch by V1 decision: **C-path** (recommended) and **A-path** (after G-3D). Tasks are atomic; each ends with a review.

---

**TA-001A — Terrain core on synthetic data**
- GOAL: prove projection, sampling interface, profile and grade logic without files or rendering.
- WHY: isolate geometry/algorithm bugs from format and Android issues.
- INPUTS: `Route`, `NavigationState` (read-only), DESIGN §6, §11, §12, §22.4 (metric definitions).
- OUTPUTS: `:terrain` module with `LocalFrame` (render-scene ENU helper), `RouteIndex` (Strategy C: haversine `s`, lat/lon grid,
  edge-local constants), `TerrainRouteProjection`, `ElevationSampler` + `SyntheticElevationSampler`, `RawElevationProfile`,
  `FilteredElevationProfile` (`FilterConfig v1`), `GradeProfile`, `DisplayElevationProfile`, `ProfileAnomalyDetector`
  (metadata only), `GradeEventDetector` (standard + short-steep rules), `EventMatcher` (G-DATA-3/4 metric implementation, reused
  unchanged by TA-001B), `TerrainPresentationState`.
- FILE AREA: `terrain/**` (new), `settings.gradle.kts` (+ `include(":terrain")`).
- FROZEN: `navigation/**`, `routing/**`, `domain/**` sources, `data/**`, `app/**`.
- SCOPE: pure Kotlin + coroutines; no Android framework calls. Excluded: GUGiK downloads, PMTiles reader, `BitmapFactory`,
  Android UI/Compose, Filament, WebView, sensor logging, real DEM archives.
- TESTS: §6.6 (incl. 100 km / 300 km axis tests, 1 mm tolerance), §22.1 profiles, attenuation table, grade/events, event matcher.
- MEASUREMENTS: projection update time on JVM for 10 000- and 30 000-point routes; §12.3 attenuation table (all columns) — reviewed
  by the operator before TA-001B starts.
- DoD: all tests pass; attenuation theory reproduced within ±0.3 pp and required event outcomes met; Raw immutability test green;
  no change outside allowed files; determinism test green.
- STOP: any need to change a frozen class → stop, raise OPEN DECISION.
- GATE: review.

**TA-001B — Real GUGiK data: pipeline + runtime reader**
- GOAL: real Terrain-RGB archive for test corridors and a verified on-device sampler.
- WHY: prove data correctness before any UI/renderer.
- INPUTS: GUGiK NMT EVRF2007 sheets for ≥ 5 routes; DESIGN §7–§11.
- OUTPUTS: `tools/terrain/` pipeline + `build_manifest.json`; `PmtilesReader`, `TerrainRgbDecoder`, `DemTileCache`,
  `PmtilesElevationSampler`; validation harness (JVM, reuses TA-001A `EventMatcher` and `FilterConfig v1` unchanged); test
  archives (not committed); report with U2–U6 answers.
- FILE AREA: `tools/terrain/**`, `terrain/**`.
- FROZEN: as TA-001A; `tools/tiles/**` unchanged; G-DATA metric definitions and thresholds (§22.3–§22.4) frozen.
- TESTS: §22.1 reader/decode; §8.4 VALIDATION on device.
- MEASUREMENTS: archive sizes (corridor 500 m/1 km), decode p95 on a mid-range device, ζ range; RAW vs FILTERED vs ANOMALY-FLAGGED
  vs OPTIONALLY-CORRECTED comparison (§12.7); full G-DATA-1…4 per-route and pooled report.
- DoD: G-DATA-1/2/3 PASS and G-DATA-4 PASS (or operator-accepted REVIEW); manifest reproducible (second build byte-identical or
  explained).
- STOP: any G-DATA FAIL → fix pipeline or re-open format decision (OD-4); G-DATA-3 INCONCLUSIVE or G-DATA-4 REVIEW → operator
  decision (OD-9); do not proceed to UI either way.
- GATE: **G-DATA**.

**TA-001C — Sensor validation tooling (ONLY IF REQUIRED)**
- GOAL: independent relative-height/grade evidence if G-DATA leaves doubt.
- WHY: source-DEM comparison cannot reveal pipeline-independent errors (e.g. road on embankment).
- INPUTS: device sensors (`TYPE_PRESSURE` if present, rotation vector, accelerometer, GPS).
- OUTPUTS: debug logger in `app/src/debug/**` (new source set) writing CSV (timestamp elapsedRealtimeNanos, pressure hPa, rotation
  quaternion, accel, GPS lat/lon/alt/speed/bearing/accuracy) at pressure 10 Hz, rotation 50 Hz, GPS 1 Hz (TARGETS); mount-angle
  calibration step (stationary 10 s on level ground); offline analysis script in `tools/terrain/validation/`.
- FROZEN: `AndroidMotionSensorSource`, roughness pipeline, `NavigationManager`, `LocationClient` (logger registers its own listeners).
- DoD: logs from ≥ 3 rides; barometric relative height vs profile over ≥ 20 events.
- GATE: review → back to G-DATA verdict.

**TA-002 — Renderer spike (A-path only, after G-3D mock-up favours A)**
- GOAL: choose Filament vs GL JS/WebView on the identical scene (§13.9).
- FILE AREA: separate spike branch/module; not merged.
- MEASUREMENTS: §21.1/§21.2 metrics on both device classes.
- DoD: report with metrics; OD-5 decided.
- STOP: both fail budgets → Option A deferred to V2; C-path continues.
- GATE: **G-3D** (spike + glance).

**TA-003 — Terrain mesh + chunk runtime (A-path)**; **TA-004 — Road ribbon + active route (A-path)**
- Per §14; DoD: seam-free chunks, ribbon never below terrain in test scene, budgets §21.

**TA-005 — Presentation interpolation / camera**
- C-path: strip window interpolation (§15.2) in Compose; A-path: 3D camera.
- DoD: simulator replay shows no backward jumps > 1 m on screen except real reversals; freeze behaviour §15.5.

**TA-006 — RIDING MAP/TERRAIN integration**
- GOAL: glove-friendly `[ MAPA ] [ TEREN ]` switch in the map box; C-path instrument visible; failure fallback.
- FILE AREA: `RidingScreen.kt` (additive), `designsystem` new components, `app/.../ui/riding/terrain/**`.
- FROZEN: `handleStopRide`, navigation calls in `RidingScreen` unchanged; `NavigationManager`, `TrackRecordingService`.
- DoD: state matrix §16 verified with simulator; MAP unaffected when terrain archive missing or terrain throws.

**TA-007 — Grade / terrain-ahead UX**
- Glance test §22.6 + sunlight §22.7 on the real implementation; thresholds tuned; DoD = pass criteria.

**TA-008 — Ride Pack architecture + terrain readiness**
- Manifest §20.3, pack layout, `TerrainReadinessEvaluator`, corridor pack build from a planned route in `tools/terrain/`.
- FROZEN: `RidePackEvaluator` unless OD-7 decides otherwise.

**TA-009 — Performance, thermal, failure recovery**
- 30-min scripted replays, §21 budgets, fault injection (corrupt tile, missing archive, renderer init failure).

**TA-010 — V1 acceptance** — §29.

Alternative branch: **IF OPTION A IS SELECTED FOR V1 (OD-1 = A)**: order becomes TA-001A → TA-001B (G-DATA) → mock-up glance →
TA-002 (G-3D) → TA-003 → TA-004 → TA-005 → TA-006 → TA-007 → TA-008 → TA-009 → TA-010.
**IF OPTION C (recommended)**: TA-001A → TA-001B (G-DATA) → TA-005(C) → TA-006 → TA-007 → TA-008 → TA-009 → TA-010 (V1);
then mock-up glance G-3D → TA-002 → TA-003/004 as V1.1/V2.

---

## 24. Exact repository file plan

### NEW

| Path | Module | Responsibility | Dependencies | Task |
|---|---|---|---|---|
| `terrain/build.gradle.kts` | :terrain | Android library, namespace `pl.mazovia.offroad.terrain`, minSdk 26, compileSdk 34 (as other modules) | `project(":domain")`, coroutines-core 1.9.0, junit | 001A |
| `terrain/src/main/AndroidManifest.xml` | :terrain | empty manifest (matches other library modules) | — | 001A |
| `terrain/src/main/java/pl/mazovia/offroad/terrain/geo/LocalFrame.kt` | :terrain | render-scene WGS84 ↔ ENU (sphere R = 6 371 km, double), rebasing — not used for `s` | — | 001A |
| `.../terrain/projection/RouteIndex.kt` | :terrain | Strategy C: haversine `s` per vertex (NavigationManager-compatible, GPX gaps 0), lat/lon edge grid, per-edge local constants | domain `Route`, `GeoPoint.distanceTo` | 001A |
| `.../terrain/projection/TerrainRouteProjection.kt` | :terrain | §6 algorithm | `RouteIndex`, domain `NavigationState` | 001A |
| `.../terrain/projection/ProjectionResult.kt` | :terrain | output model | — | 001A |
| `.../terrain/elevation/ElevationSampler.kt` | :terrain | interface + `ElevationSample` + metadata | — | 001A |
| `.../terrain/elevation/SyntheticElevationSampler.kt` | :terrain | deterministic synthetic surfaces (incl. §12.3 cases) for tests and simulator | — | 001A |
| `.../terrain/profile/RawElevationProfile.kt` | :terrain | immutable sampled heights + status + confidence at `s` positions | sampler, index | 001A |
| `.../terrain/profile/FilteredElevationProfile.kt` | :terrain | `FilterConfig v1` derived heights with raw source spans | Raw | 001A |
| `.../terrain/profile/GradeProfile.kt` | :terrain | grade from Filtered, traceable | Filtered | 001A |
| `.../terrain/profile/DisplayElevationProfile.kt` | :terrain | presentation-only heights | Filtered | 001A |
| `.../terrain/profile/ProfileAnomalyDetector.kt` | :terrain | metadata-only anomaly spans | Raw | 001A |
| `.../terrain/profile/GradeEventDetector.kt` | :terrain | standard + short-steep events + confidence | GradeProfile | 001A |
| `.../terrain/validation/EventMatcher.kt` | :terrain | pre-declared G-DATA-3/4 matching, BORDERLINE, precision/recall (single implementation for tests and TA-001B) | event model | 001A |
| `.../terrain/presentation/TerrainPresentationState.kt` | :terrain | immutable state for UI/renderer | — | 001A |
| `terrain/src/test/java/pl/mazovia/offroad/terrain/**` | :terrain | unit tests §22.1 | junit, coroutines-test | 001A/B |
| `.../terrain/dem/PmtilesReader.kt` | :terrain | minimal read-only PMTiles v3 | JDK (FileChannel, Inflater) | 001B |
| `.../terrain/dem/TerrainRgbDecoder.kt` | :terrain | PNG → heights (BitmapFactory) | Android framework | 001B |
| `.../terrain/dem/DemTileCache.kt` | :terrain | LRU decoded blocks | — | 001B |
| `.../terrain/dem/PmtilesElevationSampler.kt` | :terrain | real sampler | reader, decoder, cache | 001B |
| `.../terrain/readiness/TerrainReadinessEvaluator.kt` | :terrain | terrain coverage readiness | sampler metadata | 008 |
| `tools/terrain/README.md` | tools | pipeline usage, provenance rules | — | 001B |
| `tools/terrain/build_terrain.py` (or `.sh`) | tools | scripted pipeline §7.7 | GDAL, PMTiles writer (versions recorded) | 001B |
| `tools/terrain/manifest.schema.json` | tools | build + pack manifest schema | — | 001B/008 |
| `designsystem/src/main/java/pl/mazovia/offroad/designsystem/components/RoadAheadInstrument.kt` | :designsystem | Canvas instrument from a UI model | compose | 006 |
| `designsystem/.../components/MapTerrainSwitch.kt` | :designsystem | glove-friendly two-state switch | compose | 006 |
| `app/src/main/java/pl/mazovia/offroad/ui/riding/terrain/TerrainRideSession.kt` | :app | ride-scoped holder (archive, caches, profile) | :terrain | 006 |
| `app/src/main/java/pl/mazovia/offroad/ui/riding/terrain/TerrainPresenter.kt` | :app | collects navigation state, drives projection, maps to UI model, interpolation | :terrain, :designsystem | 005/006 |
| `app/src/debug/java/pl/mazovia/offroad/debug/NavigationStateReplayer.kt` | :app (debug) | simulator §22.2 | :terrain | 005 |

### MODIFIED

| Path | Reason | Frozen-rule proof |
|---|---|---|
| `settings.gradle.kts` | `include(":terrain")` | build config, not in AUDIT §3 |
| `app/build.gradle.kts` | `implementation(project(":terrain"))` (TA-006); renderer dependency only after TA-002 decision | not in AUDIT §3 |
| `app/src/main/java/pl/mazovia/offroad/ui/riding/RidingScreen.kt` | add switch + TERRAIN content inside the existing map `Box` (portrait lines 234-362, landscape 481-595) | not in AUDIT §3; `handleStopRide`, navigation calls, `TerrainRadar` block left unchanged |
| `app/src/main/java/pl/mazovia/offroad/ui/map/components/MapLibrePMTilesPOCContainer.kt` | A-path only: optional `paused: Boolean` parameter calling `MapView.onPause/onResume` (§19.2) | not in AUDIT §3; default preserves current behaviour |
| `app/src/main/java/pl/mazovia/offroad/ui/readiness/RidePackReadinessCard.kt` | TA-008: show terrain readiness row (separate model) | not in AUDIT §3; `RidePackEvaluator` untouched |

### UNCHANGED — FROZEN

`navigation/src/main/java/pl/mazovia/offroad/navigation/NavigationManager.kt`, `.../GpxNavigator.kt`;
`domain/src/main/java/pl/mazovia/offroad/domain/model/{NavigationState,Route,RouteSegment,RouteMetrics,Maneuver,GeoPoint}.kt`;
`domain/.../navigation/OffRouteDetector.kt`; `domain/.../routing/RoutingEngine.kt`; `domain/.../gpx/{GpxRoute,GpxParser,GpxWriter}.kt`;
`routing/src/main/java/pl/mazovia/offroad/routing/**` (incl. `TerrainRadarCalculator.kt`, D7);
`app/.../location/AndroidLocationClient.kt`; `app/.../service/TrackRecordingService.kt`; `data/.../repository/SessionRepository.kt`;
`app/src/main/java/javax/lang/model/SourceVersion.java`; `domain/.../readiness/RidePackEvaluator.kt` (§20.6).
Reason: AUDIT §3 navigation semantics, plus readiness gating semantics.

### POSSIBLE FUTURE

`terrain/.../render/**` or a renderer host in `:app` (A-path, after TA-002); `app/src/main/assets/terrain/**` (only if a bundled demo
archive is ever wanted — not planned); map/routing migration into ride packs (separate tasks); vector-PMTiles bridge/tunnel lookup (§12.4).

---

## 25. Risks

### 25.1 Technical

| # | Risk | L | I | Mitigation | Owner |
|---|---|---|---|---|---|
| T1 | Projection mis-attaches on loops/parallel sections | M | M | continuity term, tests §6.6 | 001A |
| T2 | `BitmapFactory` not bit-exact (U5) | M | M | validation test; pure-Kotlin inflate fallback | 001B |
| T3 | PMTiles reader bugs | M | M | reference-writer archives, Python reader cross-check | 001B |
| T4 | Filament compileSdk/AAR incompatibility (U7) | M | M | spike; compileSdk bump is a separate decision | 002 |
| T5 | WebView local random access (U8) | M | M | custom pmtiles Source via bridge | 002 |
| T6 | StateFlow conflation hides GPS loss vs stationary (C4) | H | L | §15.5 rule treats both as harmless freeze | 005 |
| T7 | Progress axis drifts from navigation (planar/projected `s`) | L (after C1) | H | Strategy C; 1 mm vertex-agreement tests on 100/300 km routes (§6.6) | 001A |
| T8 | Filtering attenuates short real climbs/descents | M | H | non-destructive layers; §12.3 derived budget; short-steep rule; G-DATA-4 | 001A/001B |
| T9 | Short-steep rule creates false events from DEM steps/artefacts | M | M | events kept but confidence ↓ in suspected spans; measured in G-DATA-3 FP | 001B |

### 25.2 Data

| # | Risk | L | I | Mitigation | Owner |
|---|---|---|---|---|---|
| D1 | Mixed KRON86/EVRF2007 sheets | M | M | pipeline rejects mixing; EVRF2007 only | 001B |
| D2 | Canopy/ALS gaps in forests | M | M | forest vs open comparison; confidence | 001B |
| D3 | Bridges/embankments produce false grades | H | M | flagged as SUSPECTED_PROFILE_ANOMALY (metadata, confidence ↓, not rewritten, §12.4); correction only if proven by G-DATA (§12.7); later vector bridge data | 001B/007 |
| D6 | Too few grade events in flat Mazovia for a meaningful event gate | H | M | ≥ 50 eligible events or INCONCLUSIVE; hillier corridors by operator decision (§22.4) | 001B |
| D4 | Datum confusion in validation | M | M | relative-only validation; ζ grid | 001B/001C |
| D5 | Low relief makes the feature low-value | M | H | U2 statistics early; Option C scaling | 001B |

### 25.3 Performance

| # | Risk | L | I | Mitigation | Owner |
|---|---|---|---|---|---|
| P1 | Decode spikes on main thread | L | H | all decode off main (§17) | 001B/006 |
| P2 | 3D thermal throttling | M | H | budgets, G-3D | 002/009 |
| P3 | Two GPU renderers alive | M | M | §19.2 pause | 006 |

### 25.4 Product

| # | Risk | L | I | Mitigation | Owner |
|---|---|---|---|---|---|
| R1 | 3D looks impressive but reads worse | M | H | glance test before spike; n = 5 exploratory, INCONCLUSIVE → operator (§22.6) | G-3D |
| R2 | Exaggerated vertical scale misleads | M | M | explicit scale label in C; fixed exaggeration in A | 007 |
| R3 | Surface bands wrong for GPX (UNKNOWN surfaces) | H | L | show "nieznana", not colours (C7) | 006 |

### 25.5 Mitigations and owner task

Covered per row above; any risk becoming an issue is recorded in the owning task's result document.

---

## 26. Open decisions for operator

**OD-1 V1 visual definition**
- OPTIONS: A true 3D / B pitched map / C instrument.
- RECOMMENDATION: C.
- CONSEQUENCES: A → TA-002/003/004 on the V1 critical path (high risk, highest cost); B → little product value; C → fastest V1, core reused.
- EVIDENCE: mock-up glance test (§3.6), TA-001B relief statistics.

**OD-2 Does true 3D remain V1 if C glances better?**
- OPTIONS: keep A in V1 regardless / A only if it beats C (G-3D) / drop A.
- RECOMMENDATION: A only via G-3D.
- CONSEQUENCES: regardless → cost without evidence; G-3D → evidence-driven; drop → loses future differentiation.
- EVIDENCE: §22.6 results.

**OD-3 Upgrade MapLibre Native beyond 11.11.0 (independent of Terrain)**
- OPTIONS: stay / upgrade to ≥ 11.13.1 / upgrade to 13.x.
- RECOMMENDATION: not needed for Terrain Ahead (not a terrain renderer; mapbox encoding avoids ML-9); decide in a separate MAP task.
- CONSEQUENCES: stay → no risk to MAP; upgrade → encoding fix, newer hillshade/color-relief (13.x) but regression testing of MAP.
- EVIDENCE: MAP backlog needs.

**OD-4 DEM runtime format**
- OPTIONS: PMTiles Terrain-RGB z15 / z14 / custom binary.
- RECOMMENDATION: PMTiles Terrain-RGB z15.
- CONSEQUENCES: §8.
- EVIDENCE: G-DATA (size, decode p95, U5).

**OD-5 Renderer after spike (A-path)**
- OPTIONS: Filament / GL JS WebView / defer A.
- RECOMMENDATION: decide by TA-002 metrics; default Filament if both pass.
- EVIDENCE: §21 metrics.

**OD-6 Frozen-class exceptions** — none requested.

**OD-9 G-DATA non-PASS outcomes (added in TA-000B-C1)**
- OPTIONS when G-DATA-3 is INCONCLUSIVE (< 50 eligible events): add routes / add hillier validation corridors / reduce reliance on
  the event gate for V1 / review detector thresholds as a new declared experiment. When G-DATA-4 is REVIEW: change `FilterConfig`
  (new experiment, full re-run) / accept the measured feature loss explicitly.
- RECOMMENDATION: add hillier corridors first (keeps metrics unchanged); never lower the 50-event minimum after seeing results.
- CONSEQUENCES: more data → delay but stronger gate; reduced reliance → faster, weaker evidence; threshold review → re-run cost.
- EVIDENCE: TA-001B per-route and pooled report.

**OD-7 Ride Pack / readiness integration**
- OPTIONS: separate `TerrainReadinessEvaluator` (recommended) / extend `RidePackEvaluator`.
- CONSEQUENCES: separate → zero risk to departure gating; extend → one verdict but touches readiness semantics.
- EVIDENCE: TA-008 UX review.

**OD-8 Corridor width for packs**
- OPTIONS: 500 m / 1 000 m.
- RECOMMENDATION: 500 m for C, 1 000 m if A.
- EVIDENCE: §9.3–9.4 sizes, TA-008 budget.

---

## 27. Out-of-scope backlog

Not designed, not fixed here:
1. TerrainRadar shows GPX UNKNOWN surfaces as asphalt (AUDIT R9).
2. NavigationManager GPS collector starts in `Application.onCreate` and never restarts after permission/GPS failure (AUDIT R1).
3. Always-on 1 Hz high-accuracy GPS subscription (AUDIT R2).
4. `reroute()` unwired (AUDIT F4, R11).
5. `restoreSession()` / `saveNavigationSession()` unwired (AUDIT F4, R11).
6. `GpxNavigator` unused (AUDIT F4).
7. `fix_engine.py` hazard (AUDIT F12, R10).
8. MAP blank view when map PMTiles missing (AUDIT R12).
9. TerrainRadar computed in composition; portrait/landscape look-ahead mismatch (AUDIT F8, R3).
10. Optional: hillshade layer in MAP (MapLibre Native, int-metre DEM; low value in flat terrain).
11. Routing graph / map PMTiles build provenance (AUDIT §5 items 3-4).

---

## 28. Upstream verification table

All consulted 2026-09-24.

| Tech | Version | URL | Section / symbol | Conclusion | Limitation |
|---|---|---|---|---|---|
| GUGiK NMT | current | https://www.geoportal.gov.pl/pl/dane/numeryczny-model-terenu-nmt/ | page text: 1 m ALS grid, 5 m stereo grid, KRON86/EVRF2007, WFS indexes, EPSG:2180 WMS, free use | NMT is the source; free | accuracy numbers not on page |
| GUGiK NMPT | current | https://www.geoportal.gov.pl/pl/dane/numeryczny-model-pokrycia-terenu-nmpt/ | includes buildings, trees, bridges; 0.5/1.0 m | reject for terrain | — |
| GUGiK quasi-geoid | — | https://www.gov.pl/web/gugik/modele-quasi-geoidy ; geoportal URL (404) | — | UNKNOWN (unverified upstream) | TA-001B obtains official grid |
| GUS area | 1 Jan 2025 | https://warszawa.stat.gov.pl/dane-o-wojewodztwie/wojewodztwo/ → `warunki_naturalne_2024.pdf` Tabl. 2 | total 3 555 861 ha; forest/wooded 23.8 % | area for §9.2 | — |
| PMTiles spec | v3 | https://raw.githubusercontent.com/protomaps/PMTiles/main/spec/v3/spec.md | tile types, compression, metadata | PNG DEM allowed | (from TA-000A, re-used) |
| PMTiles impls | main | https://raw.githubusercontent.com/protomaps/PMTiles/main/README.md ; `js/src/index.ts` | no Java reader listed; JS `Source.getBytes` | custom JS Source possible | — |
| Planetiler | v0.10.2 | https://raw.githubusercontent.com/onthegomap/planetiler/v0.10.2/planetiler-core/src/main/java/com/onthegomap/planetiler/pmtiles/ReadablePmtiles.java | FileChannel reader, Apache-2.0 | reference for port | depends on planetiler types |
| MapLibre Native DEM | android-v11.11.0 | https://raw.githubusercontent.com/maplibre/maplibre-native/android-v11.11.0/src/mbgl/geometry/dem_data.cpp | unpack vectors; `static_cast<int32_t>` | mapbox 0.1 m encoding; Native quantises to 1 m | — |
| MapLibre Native MapView | android-v11.11.0 | `.../platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapView.java` | `onPause` (renderer), `onStop`, `setMaximumFps` | pause option viable | behaviour under hidden composition to test |
| MapLibre style spec | main | https://raw.githubusercontent.com/maplibre/maplibre-style-spec/main/src/reference/v8.json | raster-dem encoding docs; default mapbox | mapbox default | — |
| MapLibre GL JS | v6.11.2 | https://api.github.com/repos/maplibre/maplibre-gl-js ; `LICENSE.txt` | release, BSD-3 | candidate viable | WebView specifics untested |
| Filament | v1.77.1 | https://api.github.com/repos/google/filament ; `android/build.gradle` ; `README.md` @ v1.77.1 | Apache-2.0; minSdk 21; compileSdk 37; GLES 3.0+/Vulkan 1.0; UiHelper | candidate viable | U7 |
| SceneView | v4.39.0 | https://api.github.com/repos/sceneview/sceneview ; `README.md` ; `gradle/libs.versions.toml` | Apache-2.0; MeshNode/GeometryNode; Filament 1.72.1 pin | not selected | churn |
| kotlinx StateFlow | master | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/master/kotlinx-coroutines-core/common/src/flow/StateFlow.kt | equality-based conflation | C4 | — |
| Android SensorManager | current | https://developer.android.com/reference/android/hardware/SensorManager ; `.../Sensor` | `getAltitude(p0, p)`, `TYPE_PRESSURE` | barometer API exists | device-dependent availability |
| Android Location | current | (TA-000A AND-1) | `getAltitude` WGS84 ellipsoid | datum caveat | — |

---

## 29. Definition of V1 complete

V1 (C-path) is complete when **all** hold (TARGET thresholds, measured per §21.5/§22):

| Area | Criterion |
|---|---|
| Offline | airplane mode for a full 30-min scripted ride: no network calls from terrain code (network inspector/log), instrument fully functional |
| Navigation isolation | no diff in AUDIT §3 frozen files since baseline; existing navigation/routing tests unchanged and passing; fault injection (terrain exceptions) leaves `NavigationState` stream and MAP unaffected |
| Terrain/profile correctness | G-DATA-1 PASS: DEM median ≤ 0.3 m, p95 ≤ 1.0 m vs 1 m NMT (§22.3) |
| Grade correctness | G-DATA-2 PASS (p95 ≤ 1.5 pp) and G-DATA-3 PASS: pooled precision ≥ 0.90 AND recall ≥ 0.90 on ≥ 50 eligible reference events, one-to-one IoU ≥ 0.60 / start ≤ 25 m matching, BORDERLINE and NO_EVENT_CASE handled per §22.4 (INCONCLUSIVE ≠ PASS) |
| Terrain-feature preservation | G-DATA-4 PASS or operator-accepted REVIEW; §12.3 synthetic attenuation table met on the final code; Raw profile never rewritten (immutability test) |
| Progress axis | Terrain `s` equals the NavigationManager axis within 1 mm at every vertex on 100 km and 300 km test routes and in the :app cross-check (§6.6) |
| Stable projection | replay suite: no on-screen backward jump > 1 m except scripted reversals; re-attach ≤ 2 fixes after recovery |
| Visual continuity | 30 fps p95 ≤ 33 ms during animation on mid-range; no dropped state updates |
| MAP/TERRAIN switching | ≤ 300 ms both ways on mid-range; navigation UI (maneuver, data panel) unchanged across switch |
| Missing-data fallback | missing/corrupt tiles show "brak danych" spans, never flat 0 m; missing archive → TERRAIN shows reason, MAP normal |
| Renderer failure fallback | injected instrument/renderer exception → MAP shown within 1 s, TEREN disabled, ride continues |
| Performance/memory | terrain subsystem ≤ 32 MB; decode p95 ≤ 15 ms off main thread |
| Battery/thermal | ≤ +3 % vs MAP over 30 min measured per the §21.5 protocol (charge counter, ≥ 3 MAP + ≥ 3 TERRAIN interleaved runs, mean and spread reported); no thermal warnings attributable to terrain |
| One-second glance | ≥ 80 % correct per question, ≥ 5 riders (exploratory product gate, no statistical claim, §22.6) |
| Sunlight | ≥ 70 % correct overall, no question < 60 % |
| Storage | 100 km × 500 m corridor terrain pack ≤ 50 MB |

For the A-path (if selected), add: 3D ≥ 30 fps sustained mid-range / 60 fps flagship, RAM ≤ 96 MB, battery ≤ +15 %, seam-free
terrain, ribbon never intersecting terrain in the test scene, and G-3D glance superiority over C.
