# Current Engineering Task

**Task:** MapLibre Native feasibility and isolated proof of concept while preserving OSMDroid as the verified fallback.
**Owner:** GEMINI
**Status:** IN PROGRESS
**Started:** 2026-09-18
**Target Completion:** POC-A physical verification

## Objective
Evaluate MapLibre Native as the future map-rendering candidate, proving it can render the route, follow the GPS marker, rotate heading, and survive the Android lifecycle on the target device.

## Constraints
- Do NOT delete or rewrite the existing OSMDroid implementation (`MapViewContainer.kt`).
- Do NOT modify GraphHopper routing architecture.
- Do NOT fabricate missing terrain or surface data.
- MUST implement correct MapLibre Android lifecycle management within Jetpack Compose.
- MUST be opt-in via a `MapEngine` selector, defaulting to `OSMDROID`.

## Next Steps
- Add MapLibre dependencies.
- Create `MapEngine` enum.
- Implement `MapLibreViewContainer`.
- Temporarily wire `MapEngine.MAPLIBRE` into `RidingScreen` for POC-A testing.
- Compile and prepare for physical device test.
