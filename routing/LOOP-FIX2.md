# TASK-LOOP-FIX2 evidence

Graph: `C:\AI_Projects\MazoviaGraphCurrent\graph`, read only. Baseline checkout:
`3d3be04` (includes the previously supplied `439ff2a` routing baseline).

## Diagnosis before selection changes

Measured all 18 original candidates for each of the four short-loop cases.
The candidates passing the existing 25% distance and 20% global-retrace limits
were the following; every one has a concentrated immediate reverse overlap.
No clean candidate could be selected from the existing search.

| Target/profile | Eligible original IDs | One-way local overlap (m) |
|---|---|---|
| 20 TERENOWY | s1-h5, s1-h6 | 1008, 1920 |
| 20 ODKRYWCZY | s1-h5, s1-h6 | 1008, 1080 |
| 50 TERENOWY | s1-h2, s1-h3 | 4188, 2760 |
| 50 ODKRYWCZY | s1-h2 | 4860 |

All 72 baseline observations are retained locally in
`build/benchmark-reports/loop-fix2-before.xml`, with the eight original exports
in `build/benchmark-reports/loops-before/`.

## Metric and breakpoint

For each interior route vertex, compare positions at equal arc lengths before
and after the vertex. Grow the matching reverse corridor in 12 m steps while
paired positions remain within 18 m. The maximum is `localSpikeDistanceMeters`,
measuring **one direction**, not the sum of the outward and return legs.
`localSpikeRatio` divides that maximum by actual route distance. Waypoint index
is zero-based and identifies the control point nearest the detected turnaround;
detection itself uses route geometry, so raw waypoint snapping does not gate it.

The route is not wrapped across its start/end, excluding ordinary shared access
stems. Crossings diverge immediately; scattered short overlaps never add up into
one long spike. Different vertex spacing and small coordinate noise are tolerated.
This measures immediate turnbacks, not every possible nonlocal repeated corridor.

Original visually bad winners: 1008/1008/4188/4860 m, **4.56–8.78%** of route length.
Original accepted long winners: 984/672/1824/1488 m, **0.59–1.15%**.
An absolute-only cutoff cannot separate these examples. Reject only when the
overlap exceeds **both 300 m and 2%**. The 2% breakpoint lies between measured
classes without target-specific thresholds; 300 m tolerates short local overlaps
(the focused access fixture is 200 m; final short-loop overlaps are 24–252 m).
Global retrace calculation and its 10%/20% tiers remain unchanged.

`LOCAL_WAYPOINT_SPIKE` removes a candidate before distance/terrain ranking.
Global rejection retains its existing separate `EXCESSIVE_RETRACE` reason.
Diagnostics and selected GeoJSON include local distance, ratio, rejection flag,
waypoint index and rejection reason; the HTML index shows local distance/ratio.

## Minimal generation recovery

Original 18 shapes and ranking of acceptable candidates are unchanged.
Only when none survives, try six additional headings (0,60,...,300 degrees),
alternating radius/target ratios 0.16 and 0.18; angular separation remains 68
degrees and the second radius remains 1.07 times the first. This covers two
smaller scales with complementary headings in **24 maximum calls**, without
randomness, target-specific branches, or recursive recovery.

All four long-loop cases still finish after 18 calls, including the original
150 ODKRYWCZY fallback. Recovery trials at a single smaller scale did not cover
both short target distances; alternating the two scales keeps the bound at six
extra candidates. No further tuning is part of this change.

## Selected routes

| Target/profile | Before km / retrace | After km / retrace | Local overlap before → after |
|---|---|---|---|
| 20 TERENOWY | 21.56 / 8.90% | 18.56 / 1.42% | 1008 → 252 m |
| 20 ODKRYWCZY | 22.13 / 8.68% | 20.13 / 0.72% | 1008 → 156 m |
| 50 TERENOWY | 52.64 / 7.84% | 46.92 / 0.00% | 4188 → 24 m |
| 50 ODKRYWCZY | 55.33 / 8.72% | 47.81 / 0.00% | 4860 → 24 m |
| 100 TERENOWY | 104.50 / 1.17% | unchanged | 984 m |
| 100 ODKRYWCZY | 114.08 / 5.02% | unchanged | 672 m |
| 150 TERENOWY | 159.55 / 1.73% | unchanged | 1824 m |
| 150 ODKRYWCZY | 180.23 / 1.38% fallback | unchanged | 1488 m |

Short-loop distance errors after: 7.21%, 0.66%, 6.17%, 4.37%, respectively.
Visual comparison removes the original large waypoint stems, retaining only
short overlaps on 20 km routes. Long-loop geometry is asserted point-for-point
against committed compact test fixtures extracted from the baseline exports.
Fixtures contain distance on line one, then latitude/longitude pairs, retaining
all baseline vertices. Generated GeoJSON, HTML and comparison PNG remain build
output only.

## Final validation

- `:routing:testDebugUnitTest --no-daemon --rerun-tasks`: PASS.
- `test --no-daemon`: PASS.
- `assembleDebug --no-daemon`: PASS.
- `git diff --check`: PASS.
- Eight graph-backed loop cases and regenerated exports: PASS, including exact
  full-geometry preservation for all four long routes and maximum 24 attempts.
- Biardy TERENOWY: 25.331840 km; ODKRYWCZY: 29.571639 km. PASS.
- Holubla TERENOWY: 34.388087 km; ODKRYWCZY: 33.573309 km. PASS.
- A→B benchmark: one warmup and two measured runs per case; both measured
  selections agree and match the accepted distances. A→B production code,
  marginal terrain rules, path details and GraphHopper lifecycle were not changed.
- Graph file sizes/timestamps unchanged, checked recursively by the loop test
  and again against the snapshot taken before final benchmarks. No graph writes.

Final local reports: `build/benchmark-reports/loops/index.html`,
`build/benchmark-reports/loop-fix2-comparison.png`,
`build/benchmark-reports/loop-fix2-after.xml`, and the A→B benchmark CSV files.
The root cause was selection accepting concentrated waypoint turnbacks under
the global percentage limit; terrain/distance ranking could then prefer them.
The added local geometry gate complements that existing global metric.
