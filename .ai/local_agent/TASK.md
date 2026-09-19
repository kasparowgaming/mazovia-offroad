TASK_ID: TASK-20260919-003
TASK_TYPE: IMPLEMENTATION
STATUS: DONE
EXPECTED_BRANCH: main
BASE_COMMIT: 709eef4815a84fb987278e3383fbc34ea549cb19

# Local Agent Task
# OWNER: QWEN

## Objective
Fix the defective GPS and Camera stability implementation in `MapLibrePMTilesPOCContainer.kt`. `NavigationManager.kt` is currently acceptable.

## Allowed Files
- `app/src/main/java/pl/mazovia/offroad/ui/map/components/MapLibrePMTilesPOCContainer.kt`

**FORBIDDEN**: Do NOT modify any other files.

## CRITICAL CORRECTIONS REQUIRED IN MapLibrePMTilesPOCContainer.kt

1. **MapLibre Must Follow Every Accepted Position:**
   There is NO need for a 3.0m deadband on the camera center in MapLibre. `NavigationManager` already filters out position jitter.
   MapLibre MUST update its camera target to `currentPosition` on EVERY invocation where `isFollowMode && currentPosition != null`.
   Currently, you wrapped the camera animation in `if (centerChanged || (angularDiff >= 3.0))`. This breaks the map! If the user drives straight (bearing doesn't change > 3°), the map stops following.
   **Fix:** Remove the condition that restricts the main camera animation. It should always fire in follow mode.

2. **Fix Duplicate Camera Animations:**
   You call `mapLibreMap.animateCamera(CameraUpdateFactory.zoomTo(16.0), 1000)` and then immediately call `animateCamera` again with a `CameraPosition.Builder`. This causes an animation race condition.
   **Fix:** Combine them into ONE camera animation call. Build ONE `CameraPosition` using the builder. If `centerChanged` is true, use `.zoom(16.0)` on the builder. Otherwise, omit `.zoom()` from the builder, or use `.zoom(mapLibreMap.cameraPosition.zoom)`. Call `animateCamera` exactly ONCE.

3. **Messy Types (`centerRequest`):**
   `centerRequest` is a `Long`. Do not convert it to `Double`. Use `var lastCenterRequest by remember { mutableStateOf<Long?>(null) }`.

4. **Independent Bearing Deadband:**
   Bearing has its own independent >= 3 degree deadband.
   Only apply `.bearing()` to the camera builder if:
   - `centerRequest` changed, OR
   - the difference between the incoming `bearing` and `lastCameraBearing` is >= 3.0 degrees (handling 0/360 wrap correctly).
   `lastCameraBearing` updates ONLY when bearing is actually applied.

## Expected Camera Logic Structure (Inside LaunchedEffect):
```kotlin
if (isFollowMode && currentPosition != null) {
    val centerChanged = lastCenterRequest != centerRequest
    
    // Calculate angularDiff handling nulls and 0/360 wrap
    var applyNewBearing = false
    val incomingBearing = bearing?.toDouble()
    
    if (incomingBearing != null) {
        if (lastCameraBearing == null || centerChanged) {
            applyNewBearing = true
        } else {
            val diff = kotlin.math.abs(incomingBearing - lastCameraBearing!!)
            val minDiff = kotlin.math.min(diff, 360.0 - diff)
            if (minDiff >= 3.0) {
                applyNewBearing = true
            }
        }
    }

    val builder = CameraPosition.Builder()
        .target(LatLng(currentPosition.latitude, currentPosition.longitude))
        
    // Zoom logic
    if (centerChanged) {
        builder.zoom(16.0)
    } else {
        builder.zoom(mapLibreMap.cameraPosition.zoom)
    }

    // Bearing logic
    if (applyNewBearing && incomingBearing != null) {
        builder.bearing(incomingBearing)
        lastCameraBearing = incomingBearing
    } else if (lastCameraBearing != null) {
        builder.bearing(lastCameraBearing!!)
    }

    mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), 1000)
    
    lastCenterRequest = centerRequest
}
```

## Completion Protocol
- You MUST commit the requested changes if the build and test succeed.
- Write your final execution status to `.ai/local_agent/RESULT.md`, including the `COMMIT_HASH`.
