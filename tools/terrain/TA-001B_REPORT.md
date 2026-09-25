# TA-001B — Real GUGiK data + PMTiles runtime reader: validation report

Run date 2026-09-25. Baseline `main` @ `debceb04c8845ebe151236d84a4bca1a7e94bc3e`, working tree clean before the run,
`AUDIT.md` blob `94d5d255…` MATCH, `DESIGN.md` blob `b1de916c…` MATCH. Nothing committed.

Labels: **VERIFIED** (checked against a primary source or artifact in this run) · **MEASURED** (from an artifact produced
in this run) · **DERIVED** (formula from measured values) · **TARGET** (DESIGN threshold) · **UNKNOWN** · **INCONCLUSIVE**.

## 0. Summary

| Gate / item | Result |
|---|---|
| Validation population | **DEVIATES FROM DESIGN** — 7 pre-declared deterministic straight transects, not ≥ 5 real routes; relief-stratified with hillier strata already included (see §0a OPEN DECISION) |
| G-DATA-1 DEM (z15) | **PASS on 7 pre-declared relief-stratified transects** — median 0.024 m, p95 0.096 m (TARGET ≤ 0.3 / ≤ 1.0 m), 10 000/10 000 points scored; not yet validated on a DESIGN-conformant route population |
| G-DATA-2 grade (z15) | **PASS on 7 pre-declared relief-stratified transects** — p95 0.275 pp (TARGET ≤ 1.5 pp), 8 216 positions; not yet validated on a DESIGN-conformant route population |
| G-DATA-3 events | **INCONCLUSIVE** — 36 eligible reference events < 50 (TP 36, FP 0, FN 0; P = R = 1.00) |
| G-DATA-4 feature preservation | **REVIEW** — recall 0.857 (≥ 0.80 ok), median attenuation 3.06 pp (> 2.0), 4 severe SHORT features unmatched (2 merged into longer events, 2 erased); `FilterConfig v1` alone already fails this gate and is the primary cause; the z15 DEM adds a smaller measurable contribution (+1 FN, +0.29 pp) — §15 |
| U5 BitmapFactory | **bit-exact on emulator** (API 34 x86_64): 140/140 tiles, 0 mismatches; physical device not available |
| Decode p95 ≤ 15 ms (mid-range) | **INCONCLUSIVE** — emulator 2.82 ms / desktop JVM 1.09 ms are not mid-range evidence |
| Storage | z15 5.14 MB / 100 km (DERIVED from MEASURED), TARGET ≤ 50 MB |
| OD-4 | **INCONCLUSIVE — MISSING PHYSICAL DEVICE / INSUFFICIENT EVENTS**; no invalidation condition triggered; evidence favours PMTiles z15 |
| **G-DATA overall** | **INCONCLUSIVE** — G-DATA-3 below minimum event count; G-DATA-4 REVIEW needs an OD-9 operator decision; validation population deviates from DESIGN (§0a) |

## 0a. OPEN DECISION — VALIDATION POPULATION DEVIATION

DESIGN §22.4 requires **≥ 5 real routes** (TARGET), fixed before the run, plus *optional* hillier corridors added **only by
operator decision**. The frozen TA-001B population does not meet this:

- It is **7 pre-declared deterministic TRANSECTS** (straight lines, fixed vertices, `validation/validation_routes.json`).
  They are **not real ridden routes**. The repository contains no usable real route (§2).
- It therefore **does not satisfy the DESIGN population requirement**. Every G-DATA result in this report is a result on
  these transects and not on a DESIGN-conformant route population.
- The selection was **relief-stratified, and hillier strata were deliberately included** (T2 upland; T3, T4 and T7
  escarpments; T6 valley slopes; T1 dunes). The frozen declaration states the aim: *"Hillier classes are included so that
  G-DATA-3 can reach its pre-declared minimum"*. So the hillier-corridor lever in DESIGN §22.4 has **already been used**,
  at freeze time and by the implementer, not through a separate operator decision.
- Even with that selection, only **36 eligible reference events** were produced (< 50).
- So adding more hillier corridors is **not a neutral, untouched lever**. More corridors of that kind would be a **new
  operator-approved experiment**: pre-declared and frozen before evaluation, followed by a full re-run. The operator should
  also ratify or reject the hillier strata that are already in the frozen set.

Operator decision required: accept the transect population as interim evidence, or require real routes (and/or further
declared corridors) before G-DATA can close. Until then G-DATA-1/2 are **PASS on 7 pre-declared relief-stratified
transects** only.

## 1. Upstream sources (VERIFIED, retrieved 2026-09-25)

| Source | URL | Used for |
|---|---|---|
| GUGiK NMT WFS index (EVRF2007, per year) | https://mapy.geoportal.gov.pl/wss/service/PZGIK/NumerycznyModelTerenuEVRF2007/WFS/Skorowidze | sheet discovery; per-sheet format, spacing, CRS (`uklad_xy`), datum (`uklad_h`), source (`zrodlo_danych`), date, RMSE (`blad_sr_wys`, `blad_sr_syt`), download URL; capabilities: Fees/AccessConstraints "Brak ograniczeń" |
| GUGiK NMT downloads | `https://opendata.geoportal.gov.pl/NumDaneWys/NMT/...` (per sheet, see `validation/build_manifest.json`) | 39 source sheets, SHA-256 recorded |
| Geoportal NMT page | https://www.geoportal.gov.pl/pl/dane/numeryczny-model-terenu-nmt/ | basic model per regulation of 16 Dec 2022; WFS/WMS service list; free use (DESIGN §7.1) |
| PL-geoid2021 (official, binding) | https://www.gov.pl/web/gugik/model-quasi-geoidy-pl-geoid2021-modelem-obowiazujacym → http://www.gugik.gov.pl/__data/assets/text_file/0008/236546/Model_quasi-geoidy-PL-geoid2021-PL-EVRF2007-NH.txt (sha256 `d695cf81…`) | U3 |
| PMTiles v3 spec | https://raw.githubusercontent.com/protomaps/PMTiles/main/spec/v3/spec.md (sha256 `2caa91c9…`) | header, directories, varints, tile ids (incl. vector 12/3423/1763 → 19078479), run lengths, compression enums |
| PMTiles reference implementation | PyPI `pmtiles` 3.8.1 (writer + reader) | test fixtures and expectations only |
| Android BitmapFactory / Bitmap | https://developer.android.com/reference/android/graphics/BitmapFactory.Options ; https://developer.android.com/reference/android/graphics/Bitmap | `inPremultiplied` (default true), `inScaled`, `inPreferredConfig`, `inPreferredColorSpace` exist; `getPixels` returns non-premultiplied ARGB; final evidence is the empirical U5 test |

External code: none copied. The Kotlin reader was written from the spec (CC0); DESIGN §8.3 mentioned Planetiler as a
port guide, but it was not needed and not used. The reference `pmtiles` package (BSD-3) runs only in tooling.

## 2. Validation dataset (frozen before any runtime-vs-reference comparison)

`validation/validation_routes.json` (frozen 2026-09-25, before the reader/sampler existed). **7 TRANSECTS, not real
routes**: the repository's GPX files (`test.gpx`, `test_mazovia.gpx`, `domain/src/test/resources/exact_gpx_fixture.gpx`)
are 2–20-point toy fixtures. Straight lines, 100 m vertices, 500 m corridor, total 41 254 m.

| ID | Relief class | Length m | Sheets | Sheet date |
|---|---|---|---|---|
| T1-KAMPINOS | forest, parabolic dunes | 6 116 | 8 | 2019-04-20 |
| T2-SZYDLOWIEC | southern upland | 6 267 | 8 | 2025-07-03 |
| T3-GORA-KALWARIA | Vistula escarpment, rural | 6 164 | 8 | 2019-04-14 |
| T4-PLOCK | Vistula high bank, partly urban | 5 560 | 4 | 2025-04-28 |
| T5-BLONIE | flat agricultural plain | 6 134 | 4 | 2019-04-20 |
| T6-WYSZKOW | Bug valley | 5 560 | 3 | 2025-04-17 |
| T7-WARSZAWA | Warsaw escarpment, urban | 5 455 | 4 | 2025-04-27 |

Areas were stratified by relief class. Within that stratification they were chosen only for the availability of 1 m ALS
EVRF2007 sheets. Two candidates were rejected for availability (Iłża, Warka/Pilica: only photogrammetric 1 m indexed).
Hillier relief classes were **deliberately included** (the frozen `selection_rationale` says: "so that G-DATA-3 can reach its
pre-declared minimum"). This population deviates from DESIGN's ≥ 5 real routes. See §0a.
Points: 10 000, `random.Random(20260925)`, uniform over each corridor rectangle (1 429/1 428 per transect),
CSV SHA-256 `978d4c72…` frozen and re-verified by every stage. Gate interpretation (feature-reference config, lost-feature
rule, percentile method) is written into the same file before results.

## 3. Source NMT metadata (VERIFIED from the official index)

- 39 sheets, all `NMT`, `1.00 m`, `ARC/INFO ASCII GRID`, `PL-EVRF2007-NH`, `Skaning laserowy` (ALS), filled, horizontal
  `PL-1992` (EPSG:2180; CRS taken from the index — the ASC files carry none — and written as a `.prj` sidecar).
- Dates: 2019-04-14, 2019-04-20, 2025-04-17, 2025-04-27, 2025-04-28, 2025-07-03. One datum (no KRON86 anywhere).
- Official RMSE per sheet: vertical `blad_sr_wys` 0.05–0.15 m, horizontal `blad_sr_syt` 0.06–0.30 m (U4).
- Grid: cell centres on integer PL-1992 coordinates in all sheets (asserted) → exact mosaic; nodata `-9999`
  (trapezoid sheet edges). Corridor coverage 0 uncovered lattice points.

## 4. Pipeline and tool versions (MEASURED)

The run was **stage-wise**, not a single `build_terrain.py all`: `fetch`, `build`, then Kotlin harness phase A
(`GDataHarnessTest.exportProfilePositions`), then `reference`, `fixtures`, `probe` and `geoid`, each as
`PYTHONHASHSEED=0 python tools/terrain/build_terrain.py <stage>` (sequence in `README.md`). Each archive manifest's `run.command`
records the stage that produced it (`… build_terrain.py build`). The `run` section is outside the reproducibility identity.
`all` is a convenience wrapper that runs the same Python stages in the order fetch, build, reference, probe, fixtures, geoid.
It is not what the manifest records. CPython 3.12.11 (uv 0.8.22, isolated venv in the work
dir), numpy 2.5.3, rasterio 1.5.1 / **GDAL 3.12.4**, pyproj 3.8.0 / **PROJ 9.8.1**, Pillow 12.3.0 / zlib 1.3.1,
pmtiles 3.8.1, zstandard 0.25.0 (full pin: `requirements.txt`). Resampling: GDAL warp `average` from the 1 m mosaic
to the XYZ pixel grid, exact transformer, 1 thread; decided before any evaluation and not changed afterwards.
PL-1992 → EPSG:3857 via "Poland CS92 + ETRF2000-PL to WGS 84 (1)" (null datum shift).
Reproducibility rests mainly on fixed inputs (SHA-256-pinned sheets, frozen validation set), pinned tool versions,
single-threaded warp and deterministic ordering (tiles written in sorted tile-id order; sorted inputs and metadata).
`PYTHONHASHSEED=0` is a narrower safeguard. The reference `pmtiles` writer de-duplicates tile payloads by Python
`hash(data)` (`writer.py`, `hash_to_offset`), and `bytes` hashing is salted per process. Pinning the seed makes that
de-duplication/collision path behave identically on every run. It is not the principal determinism mechanism.

## 5. Archive provenance and determinism (MEASURED)

| | z15 (primary) | z14 (comparison) |
|---|---|---|
| build_id | `ta001b-z15-d57556ef7d9c4592` | `ta001b-z14-12aa6a9523085883` |
| SHA-256 | `c8c83d8fd2976e00fe1b5e88b371de5515467beb996adbaf4445dc26bad6b7a7` | `6ecebd95e3f8a59196384e5a8a54fdd2139acc7fd8e4c93ada1ff596041ad828` |
| bytes | 2 121 778 | 1 031 016 |
| tiles | 96 | 40 |
| nodata pixels | 0 | 170 240 (outer z14 pixels beyond the downloaded z15 footprint) |
| header | PNG, tile compression none, internal gzip, root dir 341 B, no leaves | root 186 B |

Determinism: an independent second build gives byte-identical archives, identical `u5_expected_*.tsv` and identical
manifest `identity` + `output`. Only `run` (timestamp, HEAD, platform) differs by design. Full provenance:
`validation/build_manifest.json` (validates against `manifest.schema.json`).

## 6–8. Storage (MEASURED / DERIVED)

| | z15 | z14 | custom-binary probe (z15 grid) |
|---|---|---|---|
| bytes | 2 121 778 | 1 031 016 | 1 938 263 (zstd-19), 2 176 179 (deflate-9) |
| bytes per corridor km (41.25 km) | 51 432 | 24 992 | 46 983 |
| per 50 km / 100 km | 2.57 / 5.14 MB | 1.25 / 2.50 MB | 2.35 / 4.70 MB |
| raw RGB / PNG ratio | 8.90× (18.87 MB raw) | 7.63× | — |
| PMTiles overhead (file − PNG payload) | 935 B | 780 B | modelled 64 B + 16 B/tile |
| PMTiles / custom | 1.09× (zstd), 0.98× (deflate) | 1.10× / 0.98× | — |

Corridor area 20.6 km²; tile-quantised area z15 ≈ 54.4 km², z14 ≈ 90.7 km² (DERIVED, 750 m / 1.5 km tiles at 52° N).
**Caveat:** straight N-S/E-W transects are close to the best case for tile quantisation. A winding route can need
several times more tiles per km. Even 5× gives 26 MB/100 km, still inside the 50 MB TARGET (DERIVED, not measured).
The probe is labelled **CUSTOM-BINARY SIZE PROBE — NOT A PRODUCTION FORMAT**. It uses the same 0.1 m codes and tile set,
per-tile base + uint16 offsets, left/up delta prediction, zig-zag, then zstd-19 / deflate-9. It has no reader.

## 9. U5 — BitmapFactory (MEASURED on emulator)

`terrain/src/androidTest/.../BitmapFactoryU5Test`. The production `BitmapFactoryTileImageDecoder` (ARGB_8888,
`inScaled=false`, `inPremultiplied=false`, sRGB target, config/colour-space asserted) decodes **every** tile. The decoded
R,G,B stream is compared by SHA-256 with the arrays the encoder wrote (`u5_expected_*.tsv`).

| Archive | Tiles | Mismatches | Non-opaque px |
|---|---|---|---|
| terrain_z15 | 96 | 0 | 0 |
| terrain_z14 | 40 | 0 | 0 |
| u5_synthetic (all 256 values per channel, all R/G pairs, −500.0 m / 8 999.9 m extremes, 50–450 m ramp, nodata checkerboard, all-nodata tile) | 4 | 0 | 0 |

Device: emulator `sdk_phone64_x86_64`, Android 14 (API 34), x86_64, `userdebug` (WHPX, SwiftShader; isolated SDK/AVD in
the work dir).

PNG colour/alpha chunks (gAMA/cHRM/sRGB/iCCP/tRNS/PLTE). Two separate checks:
- **Production archives:** `RealArchiveJvmTest` (JVM, opt-in with `-Pterrain.gdata.workDir`) walks the chunks of **every**
  tile of both production archives (96 z15 + 40 z14) and asserts 0 such chunks. It also checks each tile's decoded RGB
  SHA-256 against the encoder (`u5_expected_*.tsv`).
- **pytest** (`test_png_has_no_colour_chunks_and_round_trips`) covers **one synthetic** 256×256 PNG from the pipeline
  encoder. It does not read the production archives.
**Bit-exactness is established for this Android build only.** A physical ARM device is still needed before U5 counts as
closed for the product.

## 10. PMTiles reader tests (MEASURED)

- JVM, always on (17): header/magic/version/truncated/out-of-bounds/negative/zoom/corrupt root; hand-encoded spec
  directory vectors; implicit offsets; run lengths; missing tiles; leaf lookup; gzip and none internal compression;
  brotli/zstd → unsupported; gzip tile compression; truncated payload; entry outside the tile section; tile-size limit;
  varint edge cases/overflow/truncation; spec tile-id vectors and round trips z0–z26.
- Reference implementation, opt-in (`ReferenceFixturesTest`): 4 archives written by the `pmtiles` writer, with
  expectations from the `pmtiles` reader — `ref_small` (197 tiles, holes, a 30-tile run), `ref_leaves` (10 000 tiles,
  15 166 B of leaf directories), and **both production archives (every tile)**. Header fields, metadata JSON and every
  tile's SHA-256 match; tile ids are cross-checked both ways.

## 11. Sampler tests (MEASURED)

`PmtilesElevationSamplerTest` (15): flat, 5 % slope, bilinear exact on planes (2 000 random points), pixel-centre
exactness, x/y tile boundary and 4-tile corner continuity (|Δ| < 1 cm), absent neighbour → nearest valid at ×0.5,
nodata neighbour → nearest valid ×0.5, all-nodata → NODATA, OUT_OF_COVERAGE (bounds, latitude, NaN), NO_TILE,
NOT_LOADED before prefetch and for an unloaded neighbour, value after prefetch, CORRUPT (negatively cached, not re-read,
one diagnostic), LRU eviction, overlapping-window prefetch refreshes the recency of already-cached tiles (F1, SR-002),
prefetch larger than the cache rejected, 16 concurrent prefetches decode each tile once, 3×3 ring. Also: `TerrainRgbTest` (8, including the exhaustive −100…2 000 m 0.1 m round trip), `DemTileCacheTest` (3).

Clarifications applied (they do not change DESIGN semantics):
1. A needed neighbour tile that is only NOT_LOADED returns `Unavailable(NOT_LOADED)` rather than a timing-dependent
   fallback value.
2. Corners with zero bilinear weight (point exactly on a pixel-centre row/column) are not consulted.
3. `ElevationSampler.prefetch(bounds)` was added as a default no-op (`SyntheticElevationSampler` is unchanged).

## 12. G-DATA-1 — DEM accuracy (MEASURED; TARGET median ≤ 0.3 m, p95 ≤ 1.0 m)

Reference: 1 m NMT, bilinear on cell centres in EPSG:2180. Runtime: `PmtilesElevationSampler` (JVM PNG path, proven
equal to the encoder RGB for all tiles). No exclusions: 0 reference nodata, 0 runtime Unavailable, 0 reduced-confidence.

| | mean | p50 | p90 | p95 | p99 | max | signed bias |
|---|---|---|---|---|---|---|---|
| **z15** | 0.034 | **0.024** | 0.067 | **0.096** | 0.210 | 2.049 | +0.0013 |
| z14 | 0.057 | 0.034 | 0.116 | 0.177 | 0.441 | 2.968 | +0.0015 |

z15 per transect p95: 0.074 (T1) … 0.115 m (T7). The max 2.05 m is in T7, urban. Worst z15 tiles by p95:
15/18255/10793 (0.27 m), 15/18260/10793, 15/18177/10736. By source year: 2019 sheets (T1, T3, T5) p95 0.074–0.105 m;
2025 sheets (T2, T4, T6, T7) 0.085–0.115 m. Land-cover stratification (forest vs open) was **not done**: no land-cover
layer is available in this task, so it is only approximated by T1 (forest). **Verdict: PASS on 7 pre-declared
relief-stratified transects** (not on a DESIGN-conformant real-route population, §0a).

## 13. G-DATA-2 — grade (MEASURED; TARGET p95 ≤ 1.5 pp)

Both sides use the same geometry, `FilterConfig-v1`, L = 25 m and 5 m positions; 8 216 scorable positions, 0 unscorable.

| | p50 | p90 | p95 | p99 | max | bias |
|---|---|---|---|---|---|---|
| **z15** | 0.067 | 0.197 | **0.275** | 0.554 | 1.445 | +0.0001 |
| z14 | 0.096 | 0.311 | 0.474 | 1.059 | 2.977 | −0.0007 |

Worst per-transect p95: z15 T5-BLONIE 0.333 pp; z14 T4-PLOCK 0.605 pp. Supplementary unfiltered (L = 25 m only) grade
p95: z15 0.426 pp, z14 0.788 pp. **Verdict: PASS on 7 pre-declared relief-stratified transects** (not on a
DESIGN-conformant real-route population, §0a).
§12.7: RAW vs FILTERED is reported above. The OPTIONALLY-CORRECTED candidate was **not** evaluated (no correction heuristic
was implemented).

## 14. G-DATA-3 — events (MEASURED; TARGET ≥ 50 eligible, P ≥ 0.90, R ≥ 0.90)

Production `GradeEventDetector` (GradeEvents-v1) + `EventMatcher` (EventMatch-v1), unchanged.

| Transect | eligible ref | ref events | runtime | TP | FP | FN | BL matched | BL ref unmatched | BL runtime |
|---|---|---|---|---|---|---|---|---|---|
| T1 | 10 | 11 | 10 | 10 | 0 | 0 | 0 | 1 | 0 |
| T2 | 4 | 5 | 5 | 4 | 0 | 0 | 1 | 0 | 0 |
| T3 | 6 | 7 | 7 | 6 | 0 | 0 | 1 | 0 | 0 |
| T4 | 7 | 7 | 7 | 7 | 0 | 0 | 0 | 0 | 0 |
| T5 | 0 | 1 | 1 | 0 | 0 | 0 | 1 | 0 | 0 — **NO_EVENT_CASE** |
| T6 | 3 | 5 | 5 | 3 | 0 | 0 | 2 | 0 | 0 |
| T7 | 6 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 0 |
| **z15 pooled** | **36** | 42 | 41 | **36** | **0** | **0** | 5 | 1 | 0 |

Precision 1.00, recall 1.00; no route requires investigation. TP diagnostics: start error median 0 m / p95 5 m, IoU
median 1.0, max-grade error median 0.16 pp / p95 0.71 pp. z14: the same 36/0/0 (BL 4/2/1).
**Verdict: INCONCLUSIVE — 36 eligible reference events < 50.** The minimum was not lowered. No transects were added after
results. The frozen set already includes hillier strata chosen to raise this count (§0a). Any further corridors would be a
new operator-approved, pre-declared experiment, not an untouched lever.

## 15. G-DATA-4 — feature preservation (MEASURED; pre-declared interpretation in validation_routes.json)

Feature reference: the detector on the 1 m reference Raw profile with only L = 25 m (`FeatureRef-L25-raw`). Runtime is
the product pipeline (z15 + FilterConfig-v1).

| | z15 | z14 | Diagnostic: 1 m reference + FilterConfig-v1 (no DEM loss) |
|---|---|---|---|
| eligible features / TP / FN | 42 / 36 / 6 | 42 / 36 / 6 | 42 / 37 / 5 |
| feature recall (TARGET ≥ 0.80) | **0.857** | 0.857 | 0.881 |
| median peak-grade attenuation (TARGET ≤ 2.0 pp) | **3.06 pp** | 3.41 pp | 2.78 pp |
| lost SHORT features with raw \|g\| ≥ 12 % (TARGET 0) | **4** | 3 | 4 (the same four) |

Elevation-change attenuation median 0.52 m; boundary shift p95 10 m (TARGET ≤ 15 m). TP by class: STANDARD 10,
SHORT 26; FN: STANDARD 1, SHORT 5.

Unmatched ("lost") severe SHORT features (10–35 m long). They fall into two different groups:

MERGED: the feature still exists after filtering, but it fails the EventMatch-v1 IoU rule (IoU ≥ 0.60) because
FilterConfig-v1 merges it into a longer STANDARD event in the same direction:
- T4, s = 475–510 m: +38.8 %, +9.4 m (escarpment / structure) → z15 CLIMB STANDARD 470–650 m, max +31.9 % (IoU ≈ 0.19)
- T7, s = 70–105 m: −13.9 %, −3.9 m → z15 DESCENT STANDARD 70–140 m, max −11.5 % (IoU = 0.50)

ERASED: after filtering, no runtime event in the same direction overlaps the feature at all:
- T3, s = 5 110–5 120 m: −15.0 %, −3.8 m
- T3, s = 5 835–5 860 m: −15.4 %, −4.3 m

(Grouping VERIFIED in F1 by re-running the production detector read-only on the frozen reference profile and the z15
archive. With the 1 m reference DEM + FilterConfig-v1 the same pattern appears: T4 → 470–645 m, T7 → 70–145 m, T3 none.)

**Verdict: REVIEW.** Causal decomposition:
- **FilterConfig-v1 alone already fails this gate.** With the 1 m reference DEM + FilterConfig-v1 and no DEM loss, median
  attenuation is 2.78 pp (> 2.0 pp) and the same four severe SHORT features are unmatched.
- **The z15 DEM adds a smaller measurable contribution:** about one additional FN (feature FN 5 → 6, recall 0.881 → 0.857)
  and about +0.29 pp median attenuation (2.78 → 3.06 pp).
- So filtering is the **primary cause** of the failing verdict, and the candidate DEM contributes a smaller, measurable
  part. This matches DESIGN §12.3 theory (a 30 m +10 % ramp keeps only 84 % of its peak). §12.3 synthetic table on the
  final code: `SmoothingAttenuationTest` is green.

## 16. Decode performance (MEASURED, not gate evidence)

| Environment | lookup+read p95 | PNG decode p95 | RGB unpack p95 | total p50 / p95 / max |
|---|---|---|---|---|
| Emulator API 34 x86_64, BitmapFactory, debug build, warm cache, 960 samples (10 × 96 tiles after 3 warm-up rounds) | 0.52 ms | 1.41 ms | 1.34 ms | 1.48 / **2.82** / 4.50 ms |
| Desktop JVM 17 (pure-Kotlin PNG decoder, not BitmapFactory), 1 920 samples | 0.018 ms | 0.99 ms | 0.08 ms | 0.99 / 1.09 / 1.91 ms |

TARGET ≤ 15 ms p95 on a mid-range device: **INCONCLUSIVE**. No physical device was connected. Emulator and desktop timings
are development evidence only. Cold-file-cache timing was not measured.

## 17. Memory / cache (DERIVED from code, MEASURED counts)

- Decoded block: 262 144 B payload (IntArray of Terrain-RGB codes, which keeps the exact 0.1 m semantics); about 262 192 B
  retained including headers. The nodata mask is the reserved code, so no extra mask array is needed.
- `DemTileCache`: 16 MiB → **63 blocks** (not 64, because headers are counted); negative entries capped at 4 096
  (tiny objects).
- Transient per decode: ARGB_8888 bitmap 256 KiB + `getPixels` IntArray 256 KiB + PNG bytes about 22 KB ≈ 0.55 MB; at
  most 2 concurrent (decode parallelism 2) ≈ 1.1 MB.
- Reader: root directory 96 entries (z15, about 5 KB of objects); leaf LRU ≤ 16 leaves (none in these archives).
- Terrain subsystem estimate ≈ 16.5 + 1.1 + < 0.1 ≈ **17.7 MB ≤ 32 MB TARGET** (DERIVED; heap not profiled on device).

## 18. U2 — relief / grade statistics (1 m reference, FilterConfig-v1; TRANSECTS, not motorcycle routes)

| Transect | \|Δh\| 300 m p50/p95/max | \|Δh\| 600 m p50/p95/max | \|g\| p50/p95/p99 % | STD / SHORT events | climbs / descents |
|---|---|---|---|---|---|
| T1 | 0.51 / 5.57 / 11.3 | 0.69 / 5.52 / 11.5 | 0.56 / 8.27 / 17.2 | 4 / 7 | 5 / 6 |
| T2 | 2.34 / 8.87 / 12.3 | 3.31 / 14.9 / 18.0 | 0.95 / 4.57 / 11.5 | 3 / 2 | 3 / 2 |
| T3 | 0.89 / 14.6 / 28.9 | 1.59 / 27.5 / 28.4 | 0.60 / 6.70 / 26.0 | 2 / 5 | 4 / 3 |
| T4 | 1.45 / 17.9 / 25.5 | 2.22 / 20.1 / 26.8 | 0.68 / 8.48 / 32.4 | 4 / 3 | 3 / 4 |
| T5 | 0.59 / 2.45 / 2.88 | 0.90 / 2.95 / 4.51 | 0.56 / 2.73 / 4.38 | 0 / 1 | 0 / 1 |
| T6 | 1.35 / 5.94 / 11.7 | 2.15 / 10.4 / 13.0 | 0.78 / 3.74 / 9.98 | 0 / 5 | 2 / 3 |
| T7 | 1.05 / 8.47 / 14.8 | 1.43 / 15.7 / 16.8 | 0.63 / 5.26 / 11.0 | 4 / 2 | 2 / 4 |

Grade histograms and event lengths are in `validation/results/gdata_results.json`. Typical (median) relief over the next
300 m is 0.5–2.3 m in every class, and events are short (median length 25–60 m). This supports Option C vertical
scaling and a SHORT-event UX. The limitation stands: these are transects, not ridden roads (roads avoid steep lines).

## 19. Unknowns

| | Status |
|---|---|
| U2 | partially resolved (transects, see §18) |
| U3 | **resolved**: official PL-geoid2021 ζ over the approximate Mazowieckie bbox 51.00–53.50 N, 19.25–23.15 E = **27.83 … 37.27 m**. Gradients on the 0.01° lattice (fixed 52.25° N spacing): **maximum per-axis gradient 0.075 m/km**
(E-W; N-S 0.054 m/km), the value stored as `max_gradient_m_per_km` in `geoid_u3.json`; **maximum 2-D gradient magnitude
≈ 0.086 m/km** (forward differences, ≈ 0.085 central; recomputed in F1 from the same model file, sha256 `d695cf81…`, and not
stored in the results file) ⇒ ≤ ~0.009 pp grade effect. Per-transect ζ change 0.06–0.18 m over ~6 km |
| U4 | **resolved for the used sheets**: official vertical RMSE 0.05–0.15 m, horizontal 0.06–0.30 m, ALS, EVRF2007, PL-1992; the regulation text itself was not re-read |
| U5 | **bit-exact on emulator API 34 x86_64**; physical device INCONCLUSIVE |
| U6 | **resolved**: PNG 8.90× (z15) / 7.63× (z14) vs raw RGB, ≈ 22 KB per z15 tile |

## 20. OD-4 recommendation (evidence-driven)

DESIGN §8.5 invalidators:

| Invalidator | Status |
|---|---|
| U5 not bit-exact without a cheap workaround | not triggered (emulator) |
| decode p95 > 15 ms without a caching remedy | not measured on mid-range; 2.8 ms on emulator |
| size > 2× custom AND > budget | not triggered: 1.09× and 5.1 MB/100 km |
| G-DATA-1/2 | PASS on 7 pre-declared relief-stratified transects (§0a) |

z14 halves storage but doubles the DEM and grade errors. It still passes G-DATA-1/2 and does not help G-DATA-4, so there is
no evidence reason to switch.
**OD-4 = INCONCLUSIVE — MISSING PHYSICAL DEVICE / INSUFFICIENT EVENTS.** No invalidation condition was triggered, and the
evidence favours **KEEP PMTILES z15**. The custom binary is not worth reopening on size (−9 %).

## 21. Limitations

- Transects, not real routes. This deviates from DESIGN's ≥ 5 real routes (§0a). Straight lines give best-case tile
  quantisation. No land-cover stratification.
- G-DATA-3 has 36 eligible events (< 50), even though hillier strata were already deliberately included. Flat Mazovia is
  the underlying cause (D6 risk materialised).
- U5 and timings come from an x86_64 emulator; there is no mid-range ARM device measurement and no cold-cache timing.
- Harness runtime heights use the JVM PNG decoder, which is proven identical to the encoder RGB (and the emulator proves
  BitmapFactory is identical too).
- §12.7 OPTIONALLY-CORRECTED comparison is not done; no forest-vs-open profile-noise comparison (§7.3).
- Only 2019 and 2025 sheet dates are represented; the reference is the same NMT (so the gates validate the pipeline, not
  NMT truth).

## 22. Next-step gate

1. **Source review before commit** (nothing committed).
2. **Operator decisions: §0a population deviation and OD-9** (G-DATA-3 INCONCLUSIVE, G-DATA-4 REVIEW). Options:
   real routes, or further pre-declared corridors beyond the hillier strata already used (either is a new operator-approved
   experiment with a full re-run); reduce reliance on the event gate; accept the measured filter attenuation; or change
   FilterConfig as a new declared experiment.
3. U5 + decode p95 on a physical mid-range ARM device (the test and push procedure are in `README.md`).
4. Do not start TA-005/TA-002 until 2–3 are decided.

## 23. F1 source-review fixes (TA-001B-F1, still uncommitted)

- **SR-001:** §0 and §0a (OPEN DECISION — validation population deviation), plus §2, §12, §13, §14, §20, §21 and §22 were
  updated. G-DATA-1/2 are qualified as PASS on 7 pre-declared relief-stratified transects. G-DATA-3 remains INCONCLUSIVE,
  G-DATA-4 remains REVIEW, and G-DATA overall remains INCONCLUSIVE.
- **Accuracy fixes:** §4 describes the stage-wise build accurately and gives the narrower PYTHONHASHSEED rationale. §9
  separates the production-archive PNG chunk check from pytest's synthetic check. §15 gives the G-DATA-4 causal split and
  merged-vs-erased features. §19 U3 separates the per-axis gradient from the 2-D magnitude.
- **SR-002 (cache recency):** `prefetchTiles` classified already-cached tiles with `DemTileCache.contains`, which does not
  refresh access order. In an overlapping prefetch those tiles could stay the oldest entries and be evicted by the same
  call's loads, so `sample()` right after a successful prefetch could return `NOT_LOADED`. The fix: already-cached tiles are
  now looked up with `cache.get` (an access, which refreshes LRU recency) for every requested key **before** any load
  starts. Capacity and eviction policy are unchanged. Regression test:
  `PmtilesElevationSamplerTest.overlappingPrefetchRefreshesRecencyOfAlreadyCachedTiles` (fails on the pre-F1 code, passes
  after).
- **Nodata value erratum:** code 0xFFFFFF decodes to −10000 + 0.1·16 777 215 = **1 667 721.5 m**. The Kotlin `TerrainRgb`
  KDoc now states this exact value. `terrain_pipeline/terrainrgb.py`'s module docstring wrongly says "1 667 711.5 m". It was
  **not edited** in F1: its SHA-256 is part of the manifest `identity.pipeline_sha256`, from which `build_id` is derived. A
  docstring edit would decouple the committed source from the frozen archives' recorded provenance. Fix it together with
  the next declared rebuild. It is documentation only and has no effect on encoding or decoding.
- **Not changed:** validation thresholds, FilterConfig, event thresholds, frozen validation set, archives, results
  JSON/TSV, NMT data.
