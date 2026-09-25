"""Stage 4: CUSTOM-BINARY SIZE PROBE — NOT A PRODUCTION FORMAT (TA-001B §34, DESIGN §8.5 invalidation rule).

Encodes exactly the same decoded elevation grid as each PMTiles archive (the tiles' Terrain-RGB codes, i.e. identical
0.1 m quantisation, identical tile set, identical nodata) as a hypothetical custom binary and reports its size. There is
no reader; the only purpose is to test "PMTiles > 2x a reasonable custom binary AND > storage budget".

Per tile: base = min valid code; values = code - base as uint16 (0xFFFF = nodata; refuses tiles whose range exceeds
65534 * 0.1 m); predictive transform = row-wise delta (first column: delta to the pixel above), zig-zag mapped to
uint16, little endian; compressed with zstd (level 19) and, for comparison, deflate (zlib level 9).
Container overhead modelled as: 64 B header + 16 B index entry per tile (tile id u64, offset u32, length u32) + 4 B base.
"""

from __future__ import annotations

import io
import json
import os
import zlib

import numpy as np
import zstandard
from PIL import Image

from . import terrainrgb


def _tile_payload(codes: np.ndarray) -> bytes:
    valid = codes != terrainrgb.NODATA_CODE
    base = int(codes[valid].min()) if valid.any() else 0
    rng = (int(codes[valid].max()) - base) if valid.any() else 0
    if rng >= 0xFFFF:
        raise ValueError("tile range exceeds 16-bit offsets")
    v = np.where(valid, codes.astype(np.int64) - base, 0xFFFF).astype(np.int64)
    pred = np.zeros_like(v)
    pred[:, 1:] = v[:, :-1]
    pred[1:, 0] = v[:-1, 0]
    d = ((v - pred + 0x8000) & 0xFFFF) - 0x8000  # delta modulo 2^16 as signed 16-bit (decoder adds back modulo 2^16)
    zz = ((d << 1) ^ (d >> 63)) & 0xFFFF  # zig-zag of a signed 16-bit value fits 16 bits
    return zz.astype("<u2").tobytes()


def run(work: str, out_dir: str, zooms, log=print) -> dict:
    from pmtiles.reader import MmapSource, all_tiles
    result = {"label": "CUSTOM-BINARY SIZE PROBE - NOT A PRODUCTION FORMAT", "method": __doc__.strip(), "archives": {}}
    cctx = zstandard.ZstdCompressor(level=19)
    for z in zooms:
        path = os.path.join(out_dir, f"terrain_z{z}.pmtiles")
        raw16 = zstd_total = deflate_total = n = 0
        with open(path, "rb") as f:
            for _, data in all_tiles(MmapSource(f)):
                rgb = np.asarray(Image.open(io.BytesIO(data)).convert("RGB"))
                payload = _tile_payload(terrainrgb.rgb_to_codes(rgb))
                raw16 += len(payload)
                zstd_total += len(cctx.compress(payload)) + 4
                deflate_total += len(zlib.compress(payload, 9)) + 4
                n += 1
        overhead = 64 + 16 * n
        result["archives"][f"z{z}"] = {
            "tiles": n, "pmtiles_bytes": os.path.getsize(path), "raw_uint16_bytes": raw16,
            "custom_zstd19_bytes": zstd_total + overhead, "custom_deflate9_bytes": deflate_total + overhead,
            "pmtiles_over_custom_zstd": os.path.getsize(path) / (zstd_total + overhead),
            "pmtiles_over_custom_deflate": os.path.getsize(path) / (deflate_total + overhead),
            "tools": {"zstandard": zstandard.__version__, "zlib": zlib.ZLIB_RUNTIME_VERSION},
        }
        log(f"probe z{z}: pmtiles {os.path.getsize(path)} B, custom zstd {zstd_total + overhead} B, deflate {deflate_total + overhead} B")
    with open(os.path.join(work, "gdata", "custom_binary_probe.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=1)
    return result
