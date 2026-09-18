# Current Engineering Task

**Task:** POC-B — verify local offline vector PMTiles rendering in MapLibre while preserving OSMDroid and GraphHopper.
**Owner:** GEMINI
**Status:** IN PROGRESS
**Started:** 2026-09-18
**Target Completion:** POC-B physical offline verification

## Objective
Prove that MapLibre Native can render vector tiles from a local PMTiles archive on the physical Android device with no network connection, using `pmtiles://file://<absolute-path>`.

## Constraints
- Do NOT modify GraphHopper routing.
- Do NOT remove OSMDroid fallback.
- Do NOT build a download manager; rely on manual ADB push for the test PMTiles file.
- Do NOT use HTTP/HTTPS basemaps in the POC-B style.
- Use the smallest possible style for rendering proof.

## Next Steps
- Determine a verifiable vector PMTiles sample file.
- Update `MapEngine` enum.
- Implement PMTiles vector source and basic layers in `MapLibreViewContainer`.
- Build the APK.
- Provide physical offline test instructions to the user.
