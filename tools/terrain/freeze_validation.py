"""Writes the frozen G-DATA validation declaration (run once, BEFORE any runtime-vs-reference evaluation).

    python tools/terrain/freeze_validation.py [--force]

Re-running without --force refuses to overwrite an existing declaration.
"""

import argparse
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from terrain_pipeline import validation_set as vs  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "validation", "validation_routes.json")

# Transects are TRANSECTS (straight deterministic lines), not motorcycle routes: the repository contains no real
# route/GPX material suitable for validation (test.gpx, test_mazovia.gpx and domain/src/test/resources/
# exact_gpx_fixture.gpx are 2-20 point toy fixtures). Areas are stratified by Mazowieckie relief class and were
# chosen from the official GUGiK index for availability of 1 m ALS EVRF2007 sheets only (Ilza and Warka/Pilica
# candidates were rejected because only photogrammetric 1 m NMT is indexed there). No error/event result existed
# when this list was written.
TRANSECTS = [
    ("T1-KAMPINOS", (52.300, 20.560), (52.355, 20.560), "forest; inland parabolic dunes (Kampinos Forest), N-S across dune belts"),
    ("T2-SZYDLOWIEC", (51.230, 20.800), (51.230, 20.890), "southern Mazovia upland (Przedgorze Ilzeckie / Szydlowiec), highest relief class"),
    ("T3-GORA-KALWARIA", (51.980, 21.150), (51.980, 21.240), "Vistula valley edge: upland plateau -> escarpment -> floodplain, rural"),
    ("T4-PLOCK", (52.590, 19.700), (52.540, 19.700), "Plock Vistula high bank (right-bank escarpment), N-S towards the river, partly urban"),
    ("T5-BLONIE", (52.200, 20.560), (52.200, 20.650), "flat agricultural plain west of Warsaw (Rownina Blonska), lowest relief class"),
    ("T6-WYSZKOW", (52.620, 21.450), (52.570, 21.450), "Bug river valley crossing near Wyszkow (terraces, valley slopes)"),
    ("T7-WARSZAWA", (52.180, 21.000), (52.180, 21.080), "Warsaw Vistula escarpment (Skarpa Warszawska), dense urban"),
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    if os.path.exists(OUT) and not a.force:
        sys.exit(f"{OUT} exists; validation set is frozen (use --force only for a new declared experiment)")
    decl = {
        "schema": "ta001b-validation-set-1",
        "frozen_at": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "frozen_before_results": True,
        "kind": "TRANSECTS",
        "kind_note": "Deterministic straight terrain-validation transects, NOT real motorcycle routes/GPX. They validate the "
                     "raster/profile/grade pipeline; they do not resolve road-specific product behaviour.",
        "coordinate_system": "WGS84 (EPSG:4326) lat/lon degrees",
        "vertex_spacing_m": 100.0,
        "corridor_half_width_m": 250.0,
        "corridor_note": "500 m total corridor (DESIGN §9.4 / OD-8 recommendation for Option C); archive tiles = XYZ tiles "
                         "intersecting the corridor rectangle.",
        "selection_rationale": "Stratified by Mazowieckie relief class; area choice constrained only by availability of "
                               "1 m ALS PL-EVRF2007-NH sheets in the official GUGiK WFS index (queried 2026-09-25). "
                               "Rejected for data availability: Ilza (51.16 N 21.24 E) and Warka/Pilica (51.78 N 21.19 E) "
                               "- only photogrammetric ('Zdj. lotnicze') 1 m NMT indexed. Hillier classes are included so "
                               "that G-DATA-3 can reach its pre-declared minimum; the minimum itself is not changed.",
        "transects": [],
        "point_sample": {
            "count": 10000,
            "seed": 20260925,
            "generator": "python random.Random(seed).random(); per transect (count split evenly, remainder to the first "
                         "transects): u~U(0,1) along start->end, v~U(-1,1) across, offset v*corridor_half_width_m along "
                         "the left normal (local equirectangular metres, R=6371000); lat/lon rounded to 9 decimals",
            "exclusion_rule": "a point is excluded (and counted) only if the 1 m reference is nodata/uncovered at the point "
                              "or the runtime sampler returns Unavailable; no other exclusion",
        },
        "profile_sampling": {"spacing_m": 5.0, "note": "RawElevationProfile.sample at 5 m on the RouteIndex axis (DESIGN §12.1)"},
        "gate_interpretation": {
            "source": "DESIGN.md §22.3-§22.4 (blob b1de916c), thresholds copied verbatim, not tuned",
            "reference_dem": "GUGiK NMT 1 m sheets used as pipeline input, bilinear on cell centres in the sheet's native CRS; "
                             "sheet priority identical to the pipeline mosaic; a reference value needs all 4 cells valid",
            "G-DATA-1": "z15 runtime vs reference at the 10 000 frozen points: median |err| <= 0.3 m AND p95 |err| <= 1.0 m; "
                        "percentiles nearest-rank; z14 reported for comparison only",
            "G-DATA-2": "every scorable 5 m position of every transect, |g_runtime - g_reference| in pp, both from "
                        "FilterConfig-v1 filtered profiles (L = 25 m): p95 <= 1.5 pp pooled; reported per transect",
            "G-DATA-3": "production GradeEventDetector (GradeEvents-v1) on FilterConfig-v1 grade of both sides; production "
                        "EventMatcher (EventMatch-v1): pooled precision >= 0.90 AND recall >= 0.90 with >= 50 eligible "
                        "reference events, else INCONCLUSIVE",
            "G-DATA-4": "feature reference = same detector on the reference Raw profile with only the L = 25 m grade window: "
                        "FilterConfig(id='FeatureRef-L25-raw', median 1, average 1, L 25); runtime = product pipeline "
                        "(z15 + FilterConfig-v1); matching = EventMatcher (EventMatch-v1) with eligibility evaluated on the "
                        "feature-reference grade profile. feature recall = matcher recall TP/(TP+FN); peak-grade attenuation "
                        "per TP pair = |ref maxGrade| - |runtime maxGrade| (pp); a 'lost severe SHORT feature' = any "
                        "unmatched feature-reference event of class SHORT with |maxGrade| >= 12 % (eligible or not). "
                        "PASS iff recall >= 0.80 AND median attenuation <= 2.0 pp AND no lost severe SHORT feature, else REVIEW",
            "unscorable": "positions where either side is Unavailable are excluded and counted (DESIGN §22.3)",
        },
    }
    for tid, a0, a1, why in TRANSECTS:
        verts = vs.densify(a0, a1, decl["vertex_spacing_m"])
        decl["transects"].append({
            "id": tid, "type": "TRANSECT", "start": list(a0), "end": list(a1), "rationale": why,
            "length_m": round(vs.transect_length_m(verts), 1),
            "bbox": [min(a0[0], a1[0]), min(a0[1], a1[1]), max(a0[0], a1[0]), max(a0[1], a1[1])],
            "vertices": [list(v) for v in verts],
        })
    pts = vs.generate_points(decl)
    decl["point_sample"]["csv_sha256"] = vs.points_sha256(pts)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(decl, f, indent=1)
        f.write("\n")
    print(f"wrote {OUT}: {len(decl['transects'])} transects, {len(pts)} points, sha {decl['point_sample']['csv_sha256']}")


if __name__ == "__main__":
    main()
