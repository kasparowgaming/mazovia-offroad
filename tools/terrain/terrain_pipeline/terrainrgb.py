"""Terrain-RGB "mapbox" encoding (DESIGN §8.4, §11.4).

height_m = -10000 + 0.1 * (R * 65536 + G * 256 + B)

- quantisation 0.1 m, encoder rounds half to even (numpy.rint) on (h + 10000) * 10;
- nodata = reserved code 0xFFFFFF (RGB 255,255,255 -> would decode to 1 667 711.5 m, far outside terrain);
- valid encodable heights: [MIN_VALID_M, MAX_VALID_M); anything else aborts the build (never clamped);
- a missing sample is never 0 m.
The Kotlin runtime (`TerrainRgb`) uses the same constants.
"""

import numpy as np

NODATA_CODE = 0xFFFFFF
NODATA_RGB = (255, 255, 255)
MIN_VALID_M = -500.0
MAX_VALID_M = 9000.0
ENCODING = "mapbox"
FORMULA = "height_m = -10000 + 0.1 * (R * 65536 + G * 256 + B)"


class EncodingError(ValueError):
    pass


def encode_codes(heights: np.ndarray) -> np.ndarray:
    """float heights (NaN = nodata) -> uint32 codes."""
    h = np.asarray(heights, dtype=np.float64)
    valid = np.isfinite(h)
    bad = valid & ((h < MIN_VALID_M) | (h >= MAX_VALID_M))
    if bad.any():
        raise EncodingError(f"{int(bad.sum())} heights outside [{MIN_VALID_M}, {MAX_VALID_M}) m; refusing to clamp")
    codes = np.full(h.shape, NODATA_CODE, dtype=np.int64)
    codes[valid] = np.rint((h[valid] + 10000.0) * 10.0).astype(np.int64)
    if (codes[valid] >= NODATA_CODE).any() or (codes[valid] < 0).any():
        raise EncodingError("code overflow")
    return codes.astype(np.uint32)


def codes_to_rgb(codes: np.ndarray) -> np.ndarray:
    c = np.asarray(codes, dtype=np.uint32)
    rgb = np.empty(c.shape + (3,), dtype=np.uint8)
    rgb[..., 0] = (c >> 16) & 0xFF
    rgb[..., 1] = (c >> 8) & 0xFF
    rgb[..., 2] = c & 0xFF
    return rgb


def rgb_to_codes(rgb: np.ndarray) -> np.ndarray:
    a = np.asarray(rgb, dtype=np.uint32)
    return (a[..., 0] << 16) | (a[..., 1] << 8) | a[..., 2]


def decode_codes(codes: np.ndarray) -> np.ndarray:
    """uint codes -> float64 heights with NaN for nodata."""
    c = np.asarray(codes, dtype=np.int64)
    h = -10000.0 + 0.1 * c.astype(np.float64)
    h[c == NODATA_CODE] = np.nan
    return h


def encode_height(h: float | None) -> tuple[int, int, int]:
    if h is None:
        return NODATA_RGB
    c = int(encode_codes(np.array([h]))[0])
    return (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF


def decode_rgb(r: int, g: int, b: int) -> float | None:
    c = (r << 16) | (g << 8) | b
    return None if c == NODATA_CODE else -10000.0 + 0.1 * c
