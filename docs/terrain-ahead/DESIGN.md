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

---

## 1. Executive decision

1. **V1 visual mode — recommendation: Option C "Road-Ahead instrument"** (2D/2.5D strip: next 300–600 m profile, surface bands,
   grade markers, next maneuver), rendered with Compose Canvas. **True 3D (Option A) is not abandoned**: it stays behind gate
   **G-3D** (glance test + renderer spike) and becomes V1.1/V2 only if it measurably beats Option C in the one-second glance test.
   Decision remains with the operator (§26 OD-1, OD-2).
2. **The renderer-independent terrain core is built first and serves A and C**: `TerrainRouteProjection` → `ElevationSampler` →
   `RouteElevationProfile` → `GradeEvents`, in a new module `:terrain` (depends on `:domain` only).
3. **Elevation**: GUGiK **NMT** (not NMPT), preprocessed offline into **PMTiles v3 / Terrain-RGB (mapbox encoding) lossless PNG,
   Web-Mercator XYZ z15, 256 px** (DERIVED ≈ 2.93 m ground spacing at 52.2° N). A minimal Kotlin PMTiles v3 reader is written
   against the CC0 spec with Planetiler's Apache-2.0 reader as reference. Format is re-checked at data gate **G-DATA** against a
   custom-binary alternative.
4. **No frozen-class exceptions are requested.** Navigation, routing, GPX, session and readiness classes stay untouched.
   Terrain readiness is added beside, not inside, `RidePackEvaluator`.
5. **Renderer (only if Option A proceeds)**: spike **Filament (direct)** and **MapLibre GL JS in WebView** on an identical scene;
   SceneView, raw OpenGL ES and Vulkan are rejected for the spike (§13). MapLibre Native is **not** a terrain renderer; the
   standalone MapLibre-Native DEM POC is **removed** from the roadmap (§13.1).
6. **Next task: TA-001A** — projection + synthetic elevation + profile/grade core with unit tests, no real data, no rendering.

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
- Load offline elevation, sample it, build a smoothed route elevation profile and grade events.
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
 TerrainRouteProjection  ── per route.id: RouteIndex (metric polyline, cumulative distance, edge grid)   [:terrain]
          │ ProjectionResult(distanceAlong, edge, fraction, tangent, crossTrack, mode)
          ▼
 RouteElevationProfile (built once per route.id, lazily extended ahead)  ◄── ElevationSampler ◄── DEM archive (PMTiles)
          │ profile window [s-50 m, s+600 m] (TARGET)                         [:terrain]
          ▼
 GradeEventDetector ──► TerrainPresentationState (immutable)               [:terrain]
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
class TerrainRouteProjection(frameFactory: (GeoPoint) -> LocalFrame) {
    fun onNavigationState(state: NavigationState, nowNanos: Long): ProjectionResult
}
data class ProjectionResult(
    val routeId: String?, val mode: ProjectionMode,          // ATTACHED, DETACHED, HOLD, NO_ROUTE
    val distanceAlongM: Double?, val edgeIndex: Int?, val edgeFraction: Double?,
    val projected: GeoPoint?, val tangentBearingDeg: Double?, val crossTrackM: Double?,
    val confidence: Float                                    // 0..1, presentation only
)
```

Pure Kotlin, no Android types, deterministic for a given input sequence. Never writes to `NavigationManager`.

### 6.2 Projection algorithm

Per `route.id` (built once, off main thread):
1. `RouteIndex`: convert `route.segments[*].points` to metric ENU relative to a route origin (first point) in **double**.
2. Edges = consecutive point pairs **within** each `RouteSegment`. Calculated routes: segments share their boundary vertex (C6),
   so edges are continuous; duplicate zero-length boundary edges are skipped. GPX routes: no edge across a `trkseg` boundary.
3. Cumulative distance `s` per vertex, matching `NavigationManager`'s gap rule (GPX gaps add 0) so `distanceAlong` is comparable
   with `route.totalDistanceMeters - remainingDistanceMeters` (INFERRED from `NavigationManager.kt:53-60`).
4. Uniform grid over edges, cell 50 m (TARGET: ≥ typical GPS error, small enough to keep candidate sets short).

Per update:
1. Convert `currentPosition` to the route frame.
2. Candidates = edges in cells within radius `R = 60 m` (TARGET; > `OffRouteDetector` 50 m threshold, VERIFIED `OffRouteDetector.kt:10`).
3. For each candidate: orthogonal projection clamped to the edge → `crossTrack`, `sAlong`.
4. Score = `crossTrack + λ·max(0, |sAlong − sExpected| − window)` with `sExpected = sPrev + v·Δt`, `window = 30 m + v·Δt·0.5`,
   `λ = 0.5` (all TARGET). The continuity term prevents snapping to parallel or later parts of loops/crossings.
5. Pick minimum; apply continuity rules (§6.3).

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

- Build: O(n) points + O(n) grid insertion; memory ≈ n × (2 doubles + 1 double s) + grid (DERIVED: 10 000 points ≈ 240 KB + grid).
- Update: O(k), k = edges in ≤ 9 cells (typically < 50) — sub-millisecond (INFERRED; measured in TA-001A DoD).

### 6.6 Tests (TA-001A)

straight line; gentle curve; hairpin (two edges 10 m apart, opposite directions); X-crossing; 20 m parallel out-and-back section;
closed loop (start = end); backward jitter 5 m (HOLD) and 15 m ×1 (rejected) ×2 (accepted); forward jump 200 m ×1 (rejected)
×3 (accepted); off-route 80 m for 2 fixes → DETACHED, return at 20 m → re-attach; GPX with 2 `trkseg` and 300 m gap; route
replacement mid-ride; `distanceAlong` vs `total − remainingDistanceMeters` agreement within 1 edge length on synthetic routes;
determinism (same input sequence → identical outputs).

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

| Option | Precision | Accuracy ≤ 5 km | Complexity | Verdict |
|---|---|---|---|---|
| Local ENU (tangent plane at origin, via ECEF in double) | double on CPU, float offsets on GPU | tangent-plane vertical drop d²/2R: 0.03 m at 600 m, 1.96 m at 5 km (DERIVED, R = 6 371 km) → rebase before 5 km | low (closed-form) | **chosen** |
| Local equirectangular | double | scale varies with latitude (cos term) — metre-level error over several km (INFERRED) | lowest | rejected for projection maths |
| Web Mercator | double | scale factor 1/cos(lat) ≈ 1.63 at 52° (DERIVED) must be corrected everywhere | medium | used only for tile addressing |
| UTM 34N | double | < 1 m | needs projection library | rejected (no benefit over ENU) |
| PL-1992 | double | good | needs projection on device | rejected (DEM is resampled offline; no need on device) |

### 10.3 Floating origin

- Scene origin = first attached projected point of the ride; axes **X = east, Y = north, Z = up**, metres.
- Rebase when the rider is > 1 000 m (TARGET) from the origin: new origin = current projected point; all chunk transforms updated
  (Option A); projection's route index stays in its own route frame (double), so projection is unaffected.
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

Implementations: `SyntheticElevationSampler` (TA-001A: plane, slope, sine hills, step, noise with seed) and
`PmtilesElevationSampler` (TA-001B).

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

### 12.1 Sampling

Resample the route polyline (route frame, double) every **5 m** (TARGET: ≈ 1.7 DEM samples, keeps profile arrays small:
100 km → 20 000 samples, DERIVED). Profile is computed lazily in windows ahead of the rider (e.g. next 2 km, refilled at 1 km) —
not the whole route at start — so startup cost is bounded.

### 12.2 Outlier handling

Median filter, window 5 samples (25 m, TARGET), then spike rejection: a sample deviating > 2.0 m (TARGET) from the median of its
50 m neighbourhood is replaced by linear interpolation and flagged (confidence ↓).

### 12.3 Smoothing

Moving average / Gaussian, window **25 m** (TARGET, inside the 20–30 m hypothesis). Maximum smoothing: window never exceeds 60 m
(TARGET) so short real steep transitions are not erased; tuned in TA-001B against DEM ground truth.

### 12.4 Bridges/tunnels/fords

- `RouteSegment` carries no bridge/tunnel/ford attributes (C7). The **vector PMTiles** transportation layer does carry `bridge`,
  `tunnel`, `ford` attributes (VERIFIED `tools/tiles/process.lua:43-50`) — a possible later data source (backlog, not V1).
- V1 heuristic: a dip or bump > 3 m (TARGET) over < 60 m (TARGET) returning to within 1 m of the pre-level → classify as
  "structure/embankment anomaly": interpolate linearly across for grade, mark the span low-confidence, show as neutral in UI.
- Fords: real dips; not suppressed unless the anomaly rule triggers.
- Embankments/cuts: NMT follows ground surface, road may sit on/below it; smoothing + anomaly rule limit false grade; residual
  error measured in TA-001B.

### 12.5 Grade events

- `grade(s) = (h(s + L/2) − h(s − L/2)) / L`, L = 25 m (TARGET), on the smoothed profile. Never from adjacent DEM pixels.
- Event = contiguous span with |grade| ≥ **4 %** (TARGET) for ≥ **50 m** (TARGET); ends when |grade| < **2.5 %** (hysteresis, TARGET).
- Outputs: `currentGrade`, `nextClimb/nextDescent {startDistance, length, avgGrade, maxGrade, elevationChange}`, `confidence`.
- Missing DEM in window → events not emitted for that span; `confidence = 0`; UI shows "no data", not "flat".
- TA-001B produces the distribution (histograms of |Δh| over 300/600 m and of grade) for ≥ 5 real routes (TARGET) — resolves U2 and
  calibrates thresholds for low relief.

### 12.6 Confidence

Profile span confidence = min of sample confidences in the grade window × anomaly flag factor. Separate from surface confidence
(`RoadDataConfidence`, VERIFIED `RouteSegment.kt:17`).

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

Centerline = `RouteSegment.points` (VERIFIED). Height = smoothed route profile (§12), **not** raw DEM. Width by functional class from
`highway` (TARGET semantic widths: track/path 3 m, service/unclassified 4 m, tertiary+ 6 m — visual only). Colour/pattern by
`surface` band only (no `smoothness`, `osmWayId`, `name`, `access` — C7). Ribbon lifted 0.15 m (TARGET) to avoid z-fighting.

### 14.5 Terrain-to-road blending

Recommended, visualisation-only: within 6 m (TARGET) of the centerline set terrain vertex height to road height; blend smoothly to
unmodified terrain at 20 m (TARGET), max adjustment 3 m (TARGET); beyond that, leave the conflict visible and mark low confidence.
Bridges/tunnels (anomaly spans §12.4): no blending, ribbon drawn at interpolated height. This falsifies terrain locally by design;
blended heights live only in mesh buffers and **never feed** profile, grade or sampler.

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

`sDisplay(t)` moves toward `sTarget` with bounded speed: `sDisplay += clamp(sTarget + v·(t − tFix) − sDisplay, −1 m, v·dt·1.3)`
(TARGET constants): conservative prediction up to 1.0 s past the last fix, never beyond `sTarget + 15 m` (TARGET). Interpolation
runs in the view (render thread for A, animation clock for C), not in `:terrain`.

### 15.3 Heading

ATTACHED: route tangent at `sDisplay` smoothed over 30 m of route (TARGET). DETACHED: `NavigationState.currentBearing` (held at low
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
(decode, mesh, upload spans with `Trace.beginSection`), `dumpsys batterystats` + fixed screen brightness 30-min scripted replay
(simulator §22.2) for MAP vs TERRAIN, `dumpsys thermalservice` sampling every 10 s. Each task that owns a metric records results in its
result document; values then become MEASURED.

---

## 22. Testing and validation

### 22.1 Unit tests (JVM, `:terrain`)

- Projection: §6.6 list.
- Sampler: known flat tile, synthetic 5 % slope, tile boundary continuity (|Δ| < 1 cm across edge), missing tile, corrupt tile,
  nodata, bilinear exactness at pixel centres.
- PMTiles reader: header parsing, root + leaf directory lookup, run-length entries, gzip internal compression, tile-not-found,
  truncated file → error, against archives from the reference writer.
- Terrain-RGB decode: encode/decode round trip for −100…2 000 m in 0.1 m steps.
- Grade: flat (no events), constant +10 % / −10 %, 5 m spike (rejected), 400 m climb (one event), long descent, missing-sample span
  (no event, confidence 0).

### 22.2 Deterministic simulation

A debug-only `NavigationStateReplayer` in `:app` debug sources feeds a scripted sequence of `NavigationState`s (position, bearing,
speed, `status`, route replacement, gaps) into the terrain presenter only — it never calls `NavigationManager` (frozen). Scripts:
straight, curve, junction, hill, descent, mixed surfaces, GPX gap, GPS loss, missing DEM tile. No motorcycle needed.

### 22.3 DEM validation (G-DATA part 1)

Runtime sampler vs source 1 m NMT (reprojected) at 10 000 random points in the test area (TARGET): median |error| ≤ 0.3 m,
p95 ≤ 1.0 m (TARGETS reflecting 2.9 m resampling + 0.1 m quantisation). z14 variant reported for comparison.

### 22.4 Grade validation (G-DATA part 2)

For ≥ 5 real routes: profile from runtime archive vs profile from 1 m source with identical smoothing: grade difference p95 ≤ 1.5 %
points; event agreement ≥ 90 % (TARGETS). Only if unresolved: TA-001C sensor comparison (barometer relative height, IMU pitch after
mount calibration) with GPS altitude used only as a weak cross-check.

### 22.5 Renderer validation

Option C: screenshot tests of the instrument for scripted states. Option A: visual check that ribbon never intersects terrain in the
test scene, no gaps at chunk seams, active route visible in all states.

### 22.6 One-second glance test

1. 12 frames per candidate (same snippets), randomised order, on the target phone in a handlebar mount at arm's length.
2. Show 1.0 s, then blank.
3. Ask: turn direction (L/R/straight)? climb/flat/descent? junction yes/no? surface change yes/no?
4. ≥ 5 riders (TARGET), record per-question correctness and response time.
5. Pass: ≥ 80 % correct per question (TARGET); candidate comparison per §3.5.

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
- INPUTS: `Route`, `NavigationState` (read-only), DESIGN §6, §11, §12.
- OUTPUTS: `:terrain` module with `LocalFrame`, `RouteIndex`, `TerrainRouteProjection`, `ElevationSampler` + `SyntheticElevationSampler`,
  `RouteElevationProfiler`, `GradeEventDetector`, `TerrainPresentationState`.
- FILE AREA: `terrain/**` (new), `settings.gradle.kts` (+ `include(":terrain")`).
- FROZEN: `navigation/**`, `routing/**`, `domain/**` sources, `data/**`, `app/**`.
- SCOPE: pure Kotlin + coroutines; no Android framework calls.
- TESTS: §6.6, §22.1 grade + sampler (synthetic).
- MEASUREMENTS: projection update time on JVM for 10 000-point route (report).
- DoD: all tests pass; no change outside allowed files; determinism test green.
- STOP: any need to change a frozen class → stop, raise OPEN DECISION.
- GATE: review.

**TA-001B — Real GUGiK data: pipeline + runtime reader**
- GOAL: real Terrain-RGB archive for test corridors and a verified on-device sampler.
- WHY: prove data correctness before any UI/renderer.
- INPUTS: GUGiK NMT EVRF2007 sheets for ≥ 5 routes; DESIGN §7–§11.
- OUTPUTS: `tools/terrain/` pipeline + `build_manifest.json`; `PmtilesReader`, `TerrainRgbDecoder`, `DemTileCache`,
  `PmtilesElevationSampler`; test archives (not committed); report with U2–U6 answers.
- FILE AREA: `tools/terrain/**`, `terrain/**`.
- FROZEN: as TA-001A; `tools/tiles/**` unchanged.
- TESTS: §22.1 reader/decode; §8.4 VALIDATION on device.
- MEASUREMENTS: archive sizes (corridor 500 m/1 km), decode p95 on a mid-range device, ζ range.
- DoD: G-DATA passes (§22.3, §22.4), manifest reproducible (second build byte-identical or explained).
- STOP: G-DATA fails → fix pipeline or re-open format decision (OD-4); do not proceed to UI.
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
| `terrain/src/main/java/pl/mazovia/offroad/terrain/geo/LocalFrame.kt` | :terrain | WGS84 ↔ ENU (double), rebasing | — | 001A |
| `.../terrain/projection/RouteIndex.kt` | :terrain | metric edges, cumulative distance, edge grid, GPX gaps | domain `Route` | 001A |
| `.../terrain/projection/TerrainRouteProjection.kt` | :terrain | §6 algorithm | `RouteIndex`, domain `NavigationState` | 001A |
| `.../terrain/projection/ProjectionResult.kt` | :terrain | output model | — | 001A |
| `.../terrain/elevation/ElevationSampler.kt` | :terrain | interface + `ElevationSample` + metadata | — | 001A |
| `.../terrain/elevation/SyntheticElevationSampler.kt` | :terrain | deterministic synthetic surfaces for tests and simulator | — | 001A |
| `.../terrain/profile/RouteElevationProfiler.kt` | :terrain | resample, outliers, smoothing, anomaly spans | sampler, index | 001A |
| `.../terrain/profile/GradeEventDetector.kt` | :terrain | grade + events + confidence | profiler | 001A |
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

### 25.2 Data

| # | Risk | L | I | Mitigation | Owner |
|---|---|---|---|---|---|
| D1 | Mixed KRON86/EVRF2007 sheets | M | M | pipeline rejects mixing; EVRF2007 only | 001B |
| D2 | Canopy/ALS gaps in forests | M | M | forest vs open comparison; confidence | 001B |
| D3 | Bridges/embankments produce false grades | H | M | anomaly rule §12.4; later vector bridge data | 001B/007 |
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
| R1 | 3D looks impressive but reads worse | M | H | glance test before spike | G-3D |
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
| Terrain/profile correctness | G-DATA: DEM median ≤ 0.3 m, p95 ≤ 1.0 m vs source |
| Grade correctness | grade p95 difference ≤ 1.5 pp vs 1 m source; event agreement ≥ 90 % on ≥ 5 routes |
| Stable projection | replay suite: no on-screen backward jump > 1 m except scripted reversals; re-attach ≤ 2 fixes after recovery |
| Visual continuity | 30 fps p95 ≤ 33 ms during animation on mid-range; no dropped state updates |
| MAP/TERRAIN switching | ≤ 300 ms both ways on mid-range; navigation UI (maneuver, data panel) unchanged across switch |
| Missing-data fallback | missing/corrupt tiles show "brak danych" spans, never flat 0 m; missing archive → TERRAIN shows reason, MAP normal |
| Renderer failure fallback | injected instrument/renderer exception → MAP shown within 1 s, TEREN disabled, ride continues |
| Performance/memory | terrain subsystem ≤ 32 MB; decode p95 ≤ 15 ms off main thread |
| Battery/thermal | ≤ +3 % vs MAP over 30 min; no thermal warnings attributable to terrain |
| One-second glance | ≥ 80 % correct per question, ≥ 5 riders |
| Sunlight | ≥ 70 % correct overall, no question < 60 % |
| Storage | 100 km × 500 m corridor terrain pack ≤ 50 MB |

For the A-path (if selected), add: 3D ≥ 30 fps sustained mid-range / 60 fps flagship, RAM ≤ 96 MB, battery ≤ +15 %, seam-free
terrain, ribbon never intersecting terrain in the test scene, and G-3D glance superiority over C.
