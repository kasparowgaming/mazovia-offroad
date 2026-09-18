# Current Engineering Task

**Task:** Generate and validate a small custom Mazovia Offroad PMTiles prototype.
**Owner:** GEMINI
**Status:** COMPLETED
**Started:** 2026-09-18
**Completed:** 2026-09-18

## Objective
Generate a small custom PMTiles prototype for the Siedlce-area using the approved Mazovia Offroad vector schema, then inspect the ACTUAL attributes present in the resulting vector tiles.

## Accomplished
- WSL2 Ubuntu environment verified and Tilemaker v3.1.0 built from source.
- PBF data downloaded and output directories established outside Git.
- Created custom `config.json` and `process.lua` schema focusing on enduro tags.
- Successfully generated PMTiles for Siedlce area.
- Inspected generated tiles using Python script:
  - Verified `highway`, `surface`, and `tracktype` are preserved and filterable.
  - Distinct surfaces include sand, dirt, grass, gravel.
  - Distinct tracktypes include grade1-5.
