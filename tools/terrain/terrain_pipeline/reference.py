"""Stage 3: reference heights from the ORIGINAL 1 m NMT (validation only; never from PMTiles).

Reference DEM (DESIGN §22.3): the GUGiK 1 m EVRF2007 sheets used as pipeline input, sampled bilinearly on cell
centres in their native CRS (EPSG:2180) at the query location. WGS84 -> EPSG:2180 with pyproj (PROJ version recorded in
the build manifest). The exact 1 m mosaic of build.region_mosaic is used (same sheets, same priority, no resampling).
A reference value requires all 4 surrounding cells to be valid; otherwise the position is reference-nodata.

Inputs (CSV, header `key,transect_id,lat,lon`):
  <work>/gdata/points.csv             - frozen G-DATA-1 points (written here from the declaration, hash-checked)
  <work>/gdata/profile_positions.csv  - 5 m profile positions exported by the Kotlin harness (phase A)
Outputs: <same name>.reference.csv with `key,ref_height_m` (empty = reference nodata).
"""

from __future__ import annotations

import csv
import json
import os

import numpy as np
from pyproj import Transformer

from . import validation_set
from .build import region_mosaic


def bilinear(mosaic: np.ndarray, transform, e: np.ndarray, n: np.ndarray) -> np.ndarray:
    # cell (row, col) centre = (x0 + col + 0.5, y0 - row - 0.5) with x0 = transform.c, y0 = transform.f
    c = e - transform.c - 0.5
    r = transform.f - n - 0.5
    c0 = np.floor(c).astype(np.int64)
    r0 = np.floor(r).astype(np.int64)
    fx = c - c0
    fy = r - r0
    h, w = mosaic.shape
    ok = (c0 >= 0) & (r0 >= 0) & (c0 + 1 < w) & (r0 + 1 < h)
    out = np.full(e.shape, np.nan)
    idx = np.where(ok)[0]
    cc, rr = c0[idx], r0[idx]
    v00 = mosaic[rr, cc].astype(np.float64)
    v10 = mosaic[rr, cc + 1].astype(np.float64)
    v01 = mosaic[rr + 1, cc].astype(np.float64)
    v11 = mosaic[rr + 1, cc + 1].astype(np.float64)
    ax, ay = fx[idx], fy[idx]
    val = (v00 * (1 - ax) * (1 - ay) + v10 * ax * (1 - ay) + v01 * (1 - ax) * ay + v11 * ax * ay)
    out[idx] = val  # NaN propagates when any corner is nodata
    return out


def sample_csv(work: str, csv_in: str, csv_out: str, log=print) -> None:
    with open(os.path.join(work, "sources.json"), encoding="utf-8") as f:
        sources = json.load(f)
    rows = []
    with open(csv_in, encoding="ascii") as f:
        for r in csv.DictReader(f):
            rows.append(r)
    to2180 = Transformer.from_crs("EPSG:4326", "EPSG:2180", always_xy=True)
    result: dict[str, float] = {}
    for region in sources["regions"]:
        sel = [r for r in rows if r["transect_id"] == region["transect_id"]]
        if not sel:
            continue
        lat = np.array([float(r["lat"]) for r in sel])
        lon = np.array([float(r["lon"]) for r in sel])
        e, n = to2180.transform(lon, lat)
        e, n = np.asarray(e), np.asarray(n)
        paths = [os.path.join(work, "sources", s["akt_rok"], os.path.basename(s["url_do_pobrania"])) for s in region["sheets"]]
        mosaic, tr = region_mosaic(paths, (e.min() - 5, n.min() - 5, e.max() + 5, n.max() + 5))
        vals = bilinear(mosaic, tr, e, n)
        for r, v in zip(sel, vals):
            result[r["key"]] = float(v)
        log(f"reference {os.path.basename(csv_in)} {region['transect_id']}: {len(sel)} positions, "
            f"nodata {int(np.isnan(vals).sum())}")
    missing = [r["key"] for r in rows if r["key"] not in result]
    if missing:
        raise RuntimeError(f"{len(missing)} positions outside every region, e.g. {missing[:3]}")
    with open(csv_out, "w", encoding="ascii", newline="\n") as f:
        f.write("key,ref_height_m\n")
        for r in rows:
            v = result[r["key"]]
            f.write(f"{r['key']},{'' if np.isnan(v) else repr(v)}\n")


def run(decl: dict, work: str, log=print) -> None:
    gd = os.path.join(work, "gdata")
    os.makedirs(gd, exist_ok=True)
    pts = validation_set.verify_frozen(decl)
    p_csv = os.path.join(gd, "points.csv")
    with open(p_csv, "w", encoding="ascii", newline="\n") as f:
        f.write("key,transect_id,lat,lon\n")
        for pid, tid, lat, lon in pts:
            f.write(f"{pid},{tid},{lat!r},{lon!r}\n")
    sample_csv(work, p_csv, os.path.join(gd, "points.reference.csv"), log)
    prof = os.path.join(gd, "profile_positions.csv")
    if os.path.exists(prof):
        sample_csv(work, prof, os.path.join(gd, "profile_positions.reference.csv"), log)
    else:
        log(f"{prof} not found: run the Kotlin harness phase A (GDataHarnessTest.exportProfilePositions) first")
