# Current Engineering Task

**Task:** Mazovia regional vector tileset schema audit and generator selection.
**Owner:** GEMINI
**Status:** IN PROGRESS
**Started:** 2026-09-18
**Target Completion:** Data architecture report delivery

## Objective
Design the vector-tile DATA SCHEMA for Mazovia Offroad before generating the regional production-quality PMTiles archive. Evaluate and select the best vector tile generator (Tilemaker vs Planetiler), determine the PBF source, define zoom strategies, and propose a custom schema preserving critical enduro tags (surface, tracktype, highway, access, etc.).

## Constraints
- Do NOT style yet.
- Do NOT generate the full dataset yet.
- Do NOT modify Android code.
- Must preserve exact `surface`, `tracktype`, and off-road tags. Stock schemas that collapse these into `paved`/`unpaved` are rejected.

## Next Steps
- Audit stock schemas (OpenMapTiles, Protomaps, etc.).
- Compare Tilemaker vs Planetiler for Windows / PMTiles output.
- Define Regional bounds and PBF source.
- Define Target custom schema and Zoom strategy.
- Output final deliverable report.
