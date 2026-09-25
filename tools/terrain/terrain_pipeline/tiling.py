"""Web Mercator XYZ addressing (DESIGN §9.6). Pure functions, no GDAL.

Convention (shared with the Kotlin runtime `WebMercator`): world pixel coordinates at zoom z span
[0, 256 * 2^z); pixel (i, j) covers [i, i + 1) x [j, j + 1) and its value represents the terrain at the
pixel centre (i + 0.5, j + 0.5). Tile x/y = floor(pixel / 256), XYZ scheme (y down from the north edge).
"""

import math

TILE_SIZE = 256
MAX_LAT = 85.05112877980659
ORIGIN_SHIFT_M = 20037508.342789244  # pi * 6378137


def world_size_px(z: int) -> int:
    return TILE_SIZE << z


def lonlat_to_world_px(lon: float, lat: float, z: int) -> tuple[float, float]:
    """WGS84 degrees -> world pixel coordinates (double). Same formula as the Kotlin runtime."""
    n = world_size_px(z)
    x = (lon + 180.0) / 360.0 * n
    s = math.sin(math.radians(lat))
    y = (0.5 - math.log((1.0 + s) / (1.0 - s)) / (4.0 * math.pi)) * n
    return x, y


def world_px_to_lonlat(x: float, y: float, z: int) -> tuple[float, float]:
    n = world_size_px(z)
    lon = x / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * y / n))))
    return lon, lat


def lonlat_to_tile(lon: float, lat: float, z: int) -> tuple[int, int]:
    x, y = lonlat_to_world_px(lon, lat, z)
    return int(math.floor(x / TILE_SIZE)), int(math.floor(y / TILE_SIZE))


def resolution_m(z: int) -> float:
    """EPSG:3857 metres per pixel (projected, not ground distance)."""
    return 2.0 * ORIGIN_SHIFT_M / world_size_px(z)


def tile_bounds_3857(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    """(minx, miny, maxx, maxy) of tile (z, x, y) in EPSG:3857 metres."""
    span = TILE_SIZE * resolution_m(z)
    minx = -ORIGIN_SHIFT_M + x * span
    maxy = ORIGIN_SHIFT_M - y * span
    return minx, maxy - span, minx + span, maxy


def tile_bounds_lonlat(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    """(west, south, east, north) degrees."""
    w, n = world_px_to_lonlat(x * TILE_SIZE, y * TILE_SIZE, z)
    e, s = world_px_to_lonlat((x + 1) * TILE_SIZE, (y + 1) * TILE_SIZE, z)
    return w, s, e, n


def ground_spacing_m(z: int, lat: float) -> float:
    return resolution_m(z) * math.cos(math.radians(lat))
