"""Corridor -> XYZ tile set -> source region; sheet coverage selection. Deterministic, numpy only."""

from __future__ import annotations

import math

import numpy as np

from . import tiling
from .validation_set import _local_scale


def corridor_tiles(transect: dict, half_width_m: float, z: int, lattice_m: float = 10.0) -> list[tuple[int, int]]:
    """XYZ tiles at zoom z intersecting the transect's rectangular corridor (dense lattice test), sorted."""
    (la0, lo0), (la1, lo1) = transect["start"], transect["end"]
    m_lat, m_lon = _local_scale((la0 + la1) / 2)
    dx, dy = (lo1 - lo0) * m_lon, (la1 - la0) * m_lat
    length = math.hypot(dx, dy)
    px, py = -dy / length, dx / length
    us = np.append(np.arange(0.0, length, lattice_m), length)
    vs = np.append(np.arange(-half_width_m, half_width_m, lattice_m), half_width_m)
    uu, vv = np.meshgrid(us / length, vs, indexing="ij")
    east = dx * uu + px * vv
    north = dy * uu + py * vv
    lat = la0 + north / m_lat
    lon = lo0 + east / m_lon
    tiles = set()
    for a, b in zip(lat.ravel(), lon.ravel()):
        tiles.add(tiling.lonlat_to_tile(float(b), float(a), z))
    return sorted(tiles)


def tiles_lattice_lonlat(z: int, tiles, step_px: int = 8) -> tuple[np.ndarray, np.ndarray]:
    """Lattice of lon/lat points covering the given tiles (pixel-centre lattice every step_px)."""
    lons, lats = [], []
    offs = np.arange(step_px / 2.0, tiling.TILE_SIZE, step_px)
    for x, y in tiles:
        gx, gy = np.meshgrid(x * tiling.TILE_SIZE + offs, y * tiling.TILE_SIZE + offs, indexing="xy")
        n = tiling.world_size_px(z)
        lon = gx / n * 360.0 - 180.0
        lat = np.degrees(np.arctan(np.sinh(np.pi * (1.0 - 2.0 * gy / n))))
        lons.append(lon.ravel())
        lats.append(lat.ravel())
    return np.concatenate(lons), np.concatenate(lats)


def points_in_polygon(xs: np.ndarray, ys: np.ndarray, ring) -> np.ndarray:
    """Even-odd ray casting, vectorised over points."""
    inside = np.zeros(xs.shape, dtype=bool)
    n = len(ring)
    for i in range(n):
        x0, y0 = ring[i]
        x1, y1 = ring[(i + 1) % n]
        cond = (y0 > ys) != (y1 > ys)
        with np.errstate(divide="ignore", invalid="ignore"):
            xint = (x1 - x0) * (ys - y0) / (y1 - y0) + x0
        inside ^= cond & (xs < xint)
    return inside


def select_sheets(candidates, lattice_e: np.ndarray, lattice_n: np.ndarray):
    """Greedy by priority: keep a sheet iff it covers a lattice point not covered by kept higher-priority sheets."""
    covered = np.zeros(lattice_e.shape, dtype=bool)
    chosen = []
    for s in sorted(candidates, key=lambda s: s.priority_key()):
        if not s.ring_2180:
            continue
        inside = points_in_polygon(lattice_e, lattice_n, s.ring_2180)
        if (inside & ~covered).any():
            chosen.append(s)
            covered |= inside
    return chosen, int((~covered).sum()), int(covered.size)
