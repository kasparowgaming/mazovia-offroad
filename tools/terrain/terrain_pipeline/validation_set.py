"""Frozen G-DATA validation set (TA-001B §9-§10): transect geometry and the deterministic point sample.

The declaration lives in tools/terrain/validation/validation_routes.json. `generate_points` is a pure function of
that declaration (Python `random.Random(seed).random()`, whose sequence is stable across Python versions), and the
SHA-256 of its CSV output is recorded in the declaration so later runs can prove the set was not changed.
"""

from __future__ import annotations

import hashlib
import io
import json
import math
import random

EARTH_R = 6371000.0


def load(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _local_scale(lat: float) -> tuple[float, float]:
    m_lat = EARTH_R * math.pi / 180.0
    return m_lat, m_lat * math.cos(math.radians(lat))


def densify(a: tuple[float, float], b: tuple[float, float], step_m: float) -> list[tuple[float, float]]:
    """Vertices (lat, lon) from a to b every ~step_m (linear in lat/lon), 7-decimal rounding."""
    m_lat, m_lon = _local_scale((a[0] + b[0]) / 2)
    length = math.hypot((b[0] - a[0]) * m_lat, (b[1] - a[1]) * m_lon)
    n = max(1, round(length / step_m))
    return [(round(a[0] + (b[0] - a[0]) * i / n, 7), round(a[1] + (b[1] - a[1]) * i / n, 7)) for i in range(n + 1)]


def transect_length_m(vertices) -> float:
    total = 0.0
    for (la0, lo0), (la1, lo1) in zip(vertices, vertices[1:]):
        m_lat, m_lon = _local_scale((la0 + la1) / 2)
        total += math.hypot((la1 - la0) * m_lat, (lo1 - lo0) * m_lon)
    return total


def generate_points(decl: dict) -> list[tuple[str, str, float, float]]:
    """(point_id, transect_id, lat, lon): uniform over each transect's rectangular corridor."""
    spec = decl["point_sample"]
    rng = random.Random(spec["seed"])
    half = decl["corridor_half_width_m"]
    transects = decl["transects"]
    total = spec["count"]
    base, extra = divmod(total, len(transects))
    out = []
    for ti, t in enumerate(transects):
        n = base + (1 if ti < extra else 0)
        (la0, lo0), (la1, lo1) = t["start"], t["end"]
        m_lat, m_lon = _local_scale((la0 + la1) / 2)
        dx, dy = (lo1 - lo0) * m_lon, (la1 - la0) * m_lat
        length = math.hypot(dx, dy)
        px, py = -dy / length, dx / length  # left normal (east, north)
        for k in range(n):
            u = rng.random()
            v = rng.random() * 2.0 - 1.0
            e = dx * u + px * v * half
            nn = dy * u + py * v * half
            lat = round(la0 + nn / m_lat, 9)
            lon = round(lo0 + e / m_lon, 9)
            out.append((f"{t['id']}-P{k:05d}", t["id"], lat, lon))
    return out


def points_csv(points) -> str:
    buf = io.StringIO()
    buf.write("point_id,transect_id,lat,lon\n")
    for pid, tid, lat, lon in points:
        buf.write(f"{pid},{tid},{lat!r},{lon!r}\n")
    return buf.getvalue()


def points_sha256(points) -> str:
    return hashlib.sha256(points_csv(points).encode("ascii")).hexdigest()


def verify_frozen(decl: dict) -> list[tuple[str, str, float, float]]:
    pts = generate_points(decl)
    got = points_sha256(pts)
    want = decl["point_sample"]["csv_sha256"]
    if got != want:
        raise RuntimeError(f"validation point set changed: {got} != frozen {want}")
    return pts
