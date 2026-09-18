# Current Engineering Task

**Task:** MapLibre Native feasibility and isolated proof of concept while preserving OSMDroid as the verified fallback.
**Owner:** GEMINI
**Status:** COMPLETED
**Started:** 2026-09-18
**Target Completion:** POC-A physical verification

## Objective
Evaluate MapLibre Native as the future map-rendering candidate, proving it can render the route, follow the GPS marker, rotate heading, and survive the Android lifecycle on the target device.

## Outcome
- MapLibre POC-A successfully proved rendering/integration on the physical device.
- OSMDroid remains the verified fallback.
- MapLibre is now the preferred candidate for continued investigation.
- No production migration decision is final until offline PMTiles and custom styling are tested.

## Next Steps
- POC-B: local offline PMTiles rendering in MapLibre.
