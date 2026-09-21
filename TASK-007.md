# TASK-007 — product trust and workflow completion

Base: main `b931121`, the validated and committed LOOP-FIX2 change.

## Workflow results

**POST-RIDE TERRAIN: FIXED.** Removed the distance-dependent fake 100%.
The summary and ride history display “Brak danych” for unclassified recordings.
An explicit persisted `terrainClassificationAvailable` flag is required before
displaying the measured percentage. No classifier or heuristic was introduced.
Legacy rides receive false through an additive Room 1→2 migration, preserving
their data. A classified ride can still display its actual measured percentage.

**POST-RIDE FEEDBACK: FIXED.** Recording now finishes its location writer and
atomically persists the ride and points before stopping the service and opening
the exact ride's summary. Paused recording can also be saved. Failures retain
the in-memory recording for retry instead of showing an older ride as completed.
The completed ride ID survives activity recreation. An unrecorded navigation
session reports that no track exists and offers return to planning.

“Zapisz i oceń drogi” verifies persistence, loads existing unanswered questions
for that ride, and presents one question at a time. Only answers valid for the
question are offered. The existing feedback repository now updates the stored
answer; the ride's pending IDs are synchronized. Retry is idempotent after a
successful answer followed by an interrupted completion update. The completion
state explicitly says there are no remaining questions. Users can return through
ride history. No new question generator was added: when there are no existing
questions, the screen honestly reports that there is nothing to rate.

**SAVED ROUTES: FIXED.** Stored serialized Route data is decoded and validated,
then opened in the existing map preview. “Prowadź” uses the same route, navigation
manager and recording start as a calculated route. Export uses the existing
GpxWriter and Android document picker. Delete requires a confirmation dialog;
requesting or cancelling deletion performs no repository deletion. Missing or
corrupt route data produces a safe message rather than an exception string.
No editing or GPX redesign was added.

**RIDING ZOOM: FIXED.** Plus/minus controls in portrait and landscape drive
MapLibre/PMTiles or OSMDroid camera zoom through a shared bounded request
consumer. They do not change navigation or follow state. Follow updates preserve
the chosen zoom; explicit recenter retains the existing reset-to-follow behavior.
Programmatic zoom does not invoke the gesture pan callback.

**MISSING GPS / WARSAW FALLBACK: FIXED.** Route calculation requires an actual
location. Without it, the request stops, stale preview data is cleared and the
rider sees a GPS explanation and retry action. Search anchoring and the initial
map camera were left unchanged; neither supplies a routing origin. A saved-route
preview can open without GPS, but following it requires GPS and permission.

**FAILURE LANGUAGE: FIXED.** Affected GPS, permission, route calculation, offline
data, navigation start, saved-route load/delete, feedback, ride save/load and GPX
export failures explain the failure, consequence and next action in Polish.
Exception details remain in logs. The former “load GraphHopper graph” prompt in
the map was replaced with rider-facing offline-data wording.

**DO-PUNKTU END-TO-END: PASS (workflow tests and wiring review).** The formerly
inactive Routes tab entry now opens the existing map flow. Map long-press or
search-result selection calls setDestination; the returned route fills the preview;
Prowadź starts navigation, recording, and RIDING mode. The JVM test checks actual
origin, preview state, navigation route, recording callback and app mode. Android
service and native map interaction have not been exercised on a device in this task.

## Validation

- Focused `:app:testDebugUnitTest :routing:testDebugUnitTest --no-daemon`: PASS.
- `test --no-daemon`: PASS (debug and release suites).
- `assembleDebug --no-daemon`: PASS.
- `git diff --check`: PASS.
- Final focused routing tests plus eight graph-backed loop exports and four A→B
  cases: PASS. A→B uses a warmup and two measured iterations per case.
- Biardy TERENOWY: 25.331840 km; ODKRYWCZY: 29.571639 km.
- Holubla TERENOWY: 34.388087 km; ODKRYWCZY: 33.573309 km.
- All eight loop GeoJSON files have identical SHA-256 hashes before and after
  TASK-007, including all four preserved 100/150 km geometries.
- Graph unchanged: recursive file sizes/timestamps checked by the graph test
  and independently against the pre-validation snapshot.
- `adb devices` returned no connected devices/emulators. Manual checks A–E were
  therefore not run; JVM workflow tests do not substitute for native UI testing.

ROUTING BEHAVIOR CHANGED: NO.
LOOP BEHAVIOR CHANGED: NO.
GRAPH MODIFIED: NO.
No production file under routing or navigation changed. Graph weighting,
terrain scoring, the marginal-terrain safeguard, search ranking, loop generation,
global retrace, local-spike thresholds and graph lifecycle are untouched.

## LOOP-FIX2 independent-audit test gaps

Missing explicit coverage was found for direction/bin boundaries, exact distance
boundaries, partial overlap below the duplicate threshold and all candidates failing.
Only tests were added to routing; no thresholds or algorithms were changed.

Added exact test names in LoopPlannerTest:

1. `direction wrap and bin boundaries preserve reverse spike detection with noise`
   — 0°, 360°, 180°, both sides of 7.5°/187.5°; noisy opposite traversal for
   local-spike detection and exact reverse geometry for the global metric.
2. `distance boundaries include exactly fifteen and twenty five percent on both sides`
   — exactly, just below and just above 15%/25%, for both short and long distances.
3. `partial shared road counts retrace but below ninety percent routes stay distinct`
   — a partial repeated road contributes retrace; substantially shared but distinct
   routes are not collapsed as duplicates.
4. `all rejected candidates produce no fabricated fallback`
   — failed routing, bad geometry, distance failure and empty input select nothing.

Shape-first policy was already explicitly covered by:

- `large local spike below global ten percent cannot beat clean terrain poorer loop`
- `low retrace fallback beats a primary distance inflated by repeated roads`

These lock the policy that unacceptable geometry is rejected before ordinary
distance/terrain ranking, including rejection of a preferred-distance candidate
in favor of a clean fallback. Their assertions and production behavior were retained.

App workflow tests added:

- `unclassified ride cannot display a fake 100 percent even with distance`
- `feedback action persists answer clears pending question and completes without duplicate ride`
- `feedback failure leaves question pending with safe retry message`
- `saved route reopens exact geometry exports and requires confirmed deletion`
- `riding zoom buttons change camera zoom exactly once and respect engine bounds`
- `routing failures have rider safe explanations and actions`
- `missing GPS never requests a Warsaw route and leaves no stale preview`
- `destination to preview to Prowadz starts navigation recording and riding`

Existing adversarial request-ordering tests now receive an explicit GPS fixture
instead of relying on the removed Warsaw routing fallback.

Build logs, graph snapshots and benchmark outputs remain ignored build artifacts.
Commit message: `TASK-007: complete trust-critical user workflows`.

## Files changed

- `TASK-007.md`
- `app/src/main/java/pl/mazovia/offroad/MainActivity.kt`
- `app/src/main/java/pl/mazovia/offroad/service/TrackRecordingService.kt`
- `app/src/main/java/pl/mazovia/offroad/state/AppModeManager.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/MazoviaNavHost.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/RiderMessages.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/map/MapScreen.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/map/MapViewModel.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/map/components/MapLibrePMTilesPOCContainer.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/map/components/MapLibreViewContainer.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/map/components/MapViewContainer.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/map/components/MapZoomState.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/postride/PostRideScreen.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/postride/PostRideViewModel.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/rides/RidesScreen.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/riding/RidingScreen.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/routes/RoutesScreen.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/routes/SavedRoutesScreen.kt`
- `app/src/main/java/pl/mazovia/offroad/ui/routes/SavedRoutesViewModel.kt`
- `app/src/test/java/pl/mazovia/offroad/ui/TrustWorkflowsTest.kt`
- `app/src/test/java/pl/mazovia/offroad/ui/map/MapViewModelTest.kt`
- `data/src/main/java/pl/mazovia/offroad/data/db/MazoviaDatabase.kt`
- `data/src/main/java/pl/mazovia/offroad/data/db/dao/RideDao.kt`
- `data/src/main/java/pl/mazovia/offroad/data/db/dao/RoadFeedbackDao.kt`
- `data/src/main/java/pl/mazovia/offroad/data/db/entity/RideEntity.kt`
- `data/src/main/java/pl/mazovia/offroad/data/repository/FeedbackRepository.kt`
- `data/src/main/java/pl/mazovia/offroad/data/repository/RideRepository.kt`
- `data/src/main/java/pl/mazovia/offroad/data/repository/RouteRepository.kt`
- `domain/src/main/java/pl/mazovia/offroad/domain/model/Ride.kt`
- `domain/src/main/java/pl/mazovia/offroad/domain/model/RoadFeedback.kt`
- `routing/src/test/java/pl/mazovia/offroad/routing/engine/LoopPlannerTest.kt`
