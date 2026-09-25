"""U3: quasi-geoid separation over Mazowieckie from the OFFICIAL GUGiK model PL-geoid2021 (PL-EVRF2007-NH).

Source (linked from https://www.gov.pl/web/gugik/model-quasi-geoidy-pl-geoid2021-modelem-obowiazujacym):
http://www.gugik.gov.pl/__data/assets/text_file/0008/236546/Model_quasi-geoidy-PL-geoid2021-PL-EVRF2007-NH.txt
Columns: latitude, longitude (0.01 deg lattice), zeta [m] = height anomaly (ellipsoidal PL-ETRF2000 GRS80 minus
normal height PL-EVRF2007-NH). Information only: G-DATA compares NMT against NMT (same datum) and never uses GPS altitude.
The Mazowieckie bbox is an approximate rectangle enclosing the voivodeship (stated in the output), not its polygon.
"""

from __future__ import annotations

import hashlib
import json
import os

import numpy as np

URL = "http://www.gugik.gov.pl/__data/assets/text_file/0008/236546/Model_quasi-geoidy-PL-geoid2021-PL-EVRF2007-NH.txt"
MAZOWIECKIE_BBOX = (51.00, 19.25, 53.50, 23.15)  # south, west, north, east (approximate enclosing rectangle)


def run(work: str, decl: dict, log=print) -> dict:
    path = os.path.join(work, "upstream", "PL-geoid2021.txt")
    if not os.path.exists(path):
        from .gugik import _http_get
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(_http_get(URL, timeout=600))
    sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
    a = np.loadtxt(path, skiprows=1)
    lat, lon, z = a[:, 0], a[:, 1], a[:, 2]
    s, w, n, e = MAZOWIECKIE_BBOX
    m = (lat >= s) & (lat <= n) & (lon >= w) & (lon <= e)
    lats = np.unique(np.round(lat[m], 2)); lons = np.unique(np.round(lon[m], 2))
    grid = np.full((lats.size, lons.size), np.nan)
    grid[np.searchsorted(lats, np.round(lat[m], 2)), np.searchsorted(lons, np.round(lon[m], 2))] = z[m]
    # gradient per km (0.01 deg ~ 1.11 km N-S, ~0.68 km E-W at 52 N)
    gy = np.abs(np.diff(grid, axis=0)) / 1.112
    gx = np.abs(np.diff(grid, axis=1)) / (1.112 * np.cos(np.radians(52.25)))
    out = {"model": "PL-geoid2021 (PL-EVRF2007-NH)", "url": URL, "sha256": sha, "lattice_deg": 0.01,
           "mazowieckie_bbox_approx": MAZOWIECKIE_BBOX, "nodes": int(m.sum()),
           "zeta_min_m": float(np.nanmin(grid)), "zeta_max_m": float(np.nanmax(grid)),
           "max_gradient_m_per_km": float(max(np.nanmax(gx), np.nanmax(gy))), "transects": {}}
    for t in decl["transects"]:
        (la0, lo0), (la1, lo1) = t["start"], t["end"]
        vals = []
        for la, lo in ((la0, lo0), (la1, lo1)):
            i = np.searchsorted(lats, la) - 1; j = np.searchsorted(lons, lo) - 1
            fy = (la - lats[i]) / 0.01; fx = (lo - lons[j]) / 0.01
            v = (grid[i, j] * (1 - fx) * (1 - fy) + grid[i, j + 1] * fx * (1 - fy) + grid[i + 1, j] * (1 - fx) * fy
                 + grid[i + 1, j + 1] * fx * fy)
            vals.append(float(v))
        out["transects"][t["id"]] = {"zeta_start_m": round(vals[0], 3), "zeta_end_m": round(vals[1], 3),
                                     "delta_over_transect_m": round(vals[1] - vals[0], 3)}
    os.makedirs(os.path.join(work, "gdata"), exist_ok=True)
    with open(os.path.join(work, "gdata", "geoid_u3.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
    log(f"U3 zeta over Mazowieckie bbox: {out['zeta_min_m']:.3f} .. {out['zeta_max_m']:.3f} m, max grad {out['max_gradient_m_per_km']:.4f} m/km")
    return out
