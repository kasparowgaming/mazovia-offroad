# Current Engineering Task

**Task:** POC-B — verify local offline vector PMTiles rendering in MapLibre while preserving OSMDroid and GraphHopper.
**Owner:** GEMINI
**Status:** COMPLETED
**Started:** 2026-09-18
**Target Completion:** POC-B physical offline verification

## Objective
Prove that MapLibre Native can render vector tiles from a local PMTiles archive on the physical Android device with no network connection, using `pmtiles://file://<absolute-path>`.

## Outcome
MapLibre successfully renders local vector PMTiles on the physical device with all network connectivity disabled.

## Next Steps
Audit/generate a Mazovia regional vector tileset preserving highway, surface, tracktype and other off-road OSM attributes, then create Mazovia Offroad Style v1.
