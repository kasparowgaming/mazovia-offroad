# tools/terrain — Terrain Ahead DEM pipeline (TA-001B)

Official GUGiK NMT 1 m (PL-EVRF2007-NH) → reproducible offline preprocessing → PMTiles v3 / Terrain-RGB (mapbox) →
consumed on device by `:terrain` (`PmtilesReader`, `TerrainRgbTileDecoder`, `DemTileCache`, `PmtilesElevationSampler`).
Design: `docs/terrain-ahead/DESIGN.md` §7–§9, §11, §22. Results: `TA-001B_REPORT.md`.

**No large data in the repository.** Source sheets, archives, CSVs and fixtures go to `--work-dir` / `--output-dir`
(default `%LOCALAPPDATA%\MazoviaOffroad\terrain-build`); the script refuses paths inside the repository.
Only small text provenance/validation files live here.

## Environment (isolated, nothing global)

```
uv venv --python 3.12 %LOCALAPPDATA%\MazoviaOffroad\terrain-build\tools\venv
uv pip install --python <venv>\Scripts\python.exe -r tools/terrain/requirements.txt
set PYTHONHASHSEED=0        # required: the reference pmtiles writer de-duplicates tiles with Python hash()
```

## One command

```
PYTHONHASHSEED=0 python tools/terrain/build_terrain.py all [--work-dir DIR] [--output-dir DIR] [--zooms 15,14]
```

Stages (`fetch | build | reference | probe | fixtures | geoid | all`):

| stage | does |
|---|---|
| fetch | queries the official WFS sheet index (`gugik:SkorowidzNMT<year>`, 2018–2026) for each frozen corridor, applies the selection rule (1.00 m, PL-EVRF2007-NH, ALS "Skaning laserowy", ARC/INFO ASCII GRID, filled sheet, newest first, redundant sheets skipped), downloads from `opendata.geoportal.gov.pl`, records SHA-256 + full index metadata, writes the CRS `.prj` sidecar from the index `uklad_xy` |
| build | exact 1 m mosaic (shared integer-centred grid, newest sheet wins) → one GDAL warp to the XYZ grid (EPSG:3857, `average`, exact transformer, 1 thread) → Terrain-RGB codes (0.1 m, half-even, nodata 0xFFFFFF) → PNG (RGB, no colour chunks) → PMTiles v3 (PNG, tile compression none, internal gzip) + `build_manifest_z<z>.json` + `u5_expected_z<z>.tsv`; label `validation` also writes `validation/build_manifest.json` |
| reference | 1 m reference heights (bilinear on cell centres, native EPSG:2180) at the frozen points and at the profile positions exported by the Kotlin harness |
| probe | CUSTOM-BINARY SIZE PROBE (not a production format) for the OD-4 size rule |
| fixtures | reader test fixtures written by the reference `pmtiles` writer + reference-reader expectations; U5 synthetic extremes archive |
| geoid | U3: official PL-geoid2021 ζ over Mazowieckie |

`all` runs them in that order; the profile-position reference needs Kotlin harness phase A first (see below).

## Validation run (G-DATA)

```
python tools/terrain/freeze_validation.py            # once, BEFORE any evaluation (refuses to overwrite)
PYTHONHASHSEED=0 python tools/terrain/build_terrain.py fetch
PYTHONHASHSEED=0 python tools/terrain/build_terrain.py build
gradlew :terrain:testDebugUnitTest --tests "*GDataHarnessTest.exportProfilePositions" -Pterrain.gdata.workDir=<work>
PYTHONHASHSEED=0 python tools/terrain/build_terrain.py reference
PYTHONHASHSEED=0 python tools/terrain/build_terrain.py fixtures
PYTHONHASHSEED=0 python tools/terrain/build_terrain.py probe
PYTHONHASHSEED=0 python tools/terrain/build_terrain.py geoid
gradlew :terrain:testDebugUnitTest -Pterrain.gdata.workDir=<work> -Pterrain.fixtures.dir=<work>/fixtures \
        -Pterrain.gdata.outDir=tools/terrain/validation/results
# U5 on a device/emulator: push terrain_z15/z14.pmtiles, u5_expected_z15/z14.tsv, u5_synthetic.pmtiles,
# u5_expected_synthetic.tsv to /data/local/tmp/ta001b, then
gradlew :terrain:connectedDebugAndroidTest
```

The harness (`terrain/src/test/.../validation/GDataHarnessTest.kt`) feeds reference and runtime heights through the
production `RawElevationProfile`, `ProfileAnomalyDetector`, `FilteredElevationProfile` (`FilterConfig.V1`),
`GradeProfile`, `GradeEventDetector` and `EventMatcher` — no event semantics exist in Python.

## Tests

```
python -m pytest tools/terrain/tests -q -p no:cacheprovider      # no network, no source data
```

## Files

| path | content |
|---|---|
| `build_terrain.py` | entry point |
| `freeze_validation.py` | writes the frozen validation declaration |
| `terrain_pipeline/` | stages (see table) |
| `manifest.schema.json` | JSON Schema of build manifests |
| `validation/validation_routes.json` | frozen transects, point generator + SHA-256, pre-declared gate interpretation |
| `validation/build_manifest.json` | provenance of the validation z15 + z14 builds |
| `validation/results/` | compact metric outputs (G-DATA, U5, probe, U3, JVM timing, encoder RGB hashes) |
| `TA-001B_REPORT.md` | validation report |

## Provenance / licence

GUGiK NMT: "Dane NMT są dostępne bezpłatnie i możliwe do dowolnego wykorzystania" (geoportal.gov.pl). Archive
metadata carries `attribution`: "Dane wysokościowe: GUGiK (NMT) — Główny Urząd Geodezji i Kartografii, geoportal.gov.pl".
The Kotlin PMTiles reader was written from the v3 specification (CC0); no third-party implementation code was copied.
