"""Stage 5: PMTiles reader test fixtures from the REFERENCE implementation (protomaps `pmtiles` Python package).

Archives are written by the reference writer; expectations (tile id, z/x/y, SHA-256 of tile bytes, missing ids,
header fields) are produced by the reference reader, never by the Kotlin reader under test. Output goes to
<work>/fixtures (outside the repository) and is consumed by the opt-in JVM test ReferenceFixturesTest
(-Pterrain.fixtures.dir=<work>/fixtures).
"""

from __future__ import annotations

import hashlib
import json
import os
import random

import pmtiles.writer as pw
from pmtiles.reader import MmapSource, Reader, all_tiles
from pmtiles.tile import Compression, TileType, tileid_to_zxy, zxy_to_tileid


def _write(path: str, tiles: dict[int, bytes], metadata: dict, force_leaves: bool) -> None:
    original = pw.optimize_directories
    if force_leaves:
        pw.optimize_directories = lambda entries, target: original(entries, 200)  # forces the reference leaf layout
    try:
        with open(path, "wb") as f:
            w = pw.Writer(f)
            for tid in sorted(tiles):
                w.write_tile(tid, tiles[tid])
            w.finalize({"tile_type": TileType.UNKNOWN, "tile_compression": Compression.NONE,
                        "min_lon_e7": -1800000000, "min_lat_e7": -850000000, "max_lon_e7": 1800000000,
                        "max_lat_e7": 850000000, "center_zoom": 0, "center_lon_e7": 0, "center_lat_e7": 0}, metadata)
    finally:
        pw.optimize_directories = original


def expectations(path: str, probe_missing: list[int]) -> dict:
    with open(path, "rb") as f:
        get = MmapSource(f)
        r = Reader(get)
        header = r.header()
        tiles = []
        for (z, x, y), data in all_tiles(get):
            tiles.append([zxy_to_tileid(z, x, y), z, x, y, hashlib.sha256(data).hexdigest(), len(data)])
        present = {t[0] for t in tiles}
        missing = [m for m in probe_missing if m not in present]
        for m in missing:
            z, x, y = tileid_to_zxy(m)
            assert r.get(z, x, y) is None
        meta = r.metadata()
    h = {k: (v.value if hasattr(v, "value") else v) for k, v in header.items()}
    return {"archive": os.path.basename(path), "header": h, "metadata": meta, "tiles": sorted(tiles), "missing": missing,
            "reference_reader": "pmtiles (Python) reader.all_tiles / Reader.get"}


def run(out_dir: str, log=print) -> None:
    os.makedirs(out_dir, exist_ok=True)
    rng = random.Random(1234)
    # F1: small archive, zooms 0-4, one hole, a run of identical tiles (run-length), root directory only
    small = {}
    for tid in range(0, 200):
        if tid in (17, 40, 150):
            continue
        small[tid] = b"same-run" if 60 <= tid < 90 else bytes(rng.randrange(256) for _ in range(rng.randrange(1, 64)))
    # F2: 10 000 tiles at z10-z12 with holes and runs, leaf directories forced through the reference layout
    big = {}
    base = zxy_to_tileid(10, 0, 0)
    tid = base
    while len(big) < 10000:
        tid += rng.choice((1, 1, 1, 2, 5))
        big[tid] = b"dup" if rng.random() < 0.1 else bytes(rng.randrange(256) for _ in range(rng.randrange(1, 40)))
    fixtures = [("ref_small.pmtiles", small, False, [17, 40, 150, 200, 5000]),
                ("ref_leaves.pmtiles", big, True, [base, base + 3, max(big) + 1, max(big) + 1000])]
    for name, tiles, leaves, missing in fixtures:
        p = os.path.join(out_dir, name)
        _write(p, tiles, {"name": name, "fixture": "TA-001B"}, leaves)
        exp = expectations(p, missing)
        with open(p + ".expected.json", "w", encoding="utf-8") as f:
            json.dump(exp, f, indent=0)
        log(f"fixture {name}: {len(exp['tiles'])} tiles, leaf dir bytes {exp['header']['leaf_directory_length']}")


def u5_synthetic(out_dir: str, log=print) -> None:
    """U5 extremes archive: every value of every channel, valid-range extremes and the reserved nodata colour.

    Tiles (z15, y=0): x=0 R=col, G=row, B=(7col+13row)&255 (all R/G pairs); x=1 R=(col+row)&255, G=(col*row)&255,
    B=col^row; x=2 heights -500.0 m .. 8999.9 m extremes + 50-450 m ramp + nodata checkerboard; x=3 all nodata.
    Expected RGB SHA-256 comes from the arrays given to the encoder.
    """
    import numpy as np
    from pmtiles.tile import zxy_to_tileid

    from . import terrainrgb
    from .build import encode_png

    col, row = np.meshgrid(np.arange(256), np.arange(256), indexing="xy")
    t0 = np.stack([col, row, (7 * col + 13 * row) & 255], axis=-1).astype(np.uint8)
    t1 = np.stack([(col + row) & 255, (col * row) & 255, col ^ row], axis=-1).astype(np.uint8)
    h = 50.0 + (col * 256 + row) / 65535.0 * 400.0
    h = np.round(h, 1)
    h[0, :] = terrainrgb.MIN_VALID_M
    h[1, :] = terrainrgb.MAX_VALID_M - 0.1
    h[(row % 7 == 3) & (col % 5 == 2)] = np.nan
    t2 = terrainrgb.codes_to_rgb(terrainrgb.encode_codes(h))
    t3 = np.full((256, 256, 3), 255, dtype=np.uint8)
    tiles = {zxy_to_tileid(15, x, 0): (x, rgb) for x, rgb in enumerate([t0, t1, t2, t3])}
    path = os.path.join(out_dir, "u5_synthetic.pmtiles")
    with open(path, "wb") as f:
        w = pw.Writer(f)
        for tid in sorted(tiles):
            w.write_tile(tid, encode_png(tiles[tid][1]))
        w.finalize({"tile_type": TileType.PNG, "tile_compression": Compression.NONE,
                    "min_lon_e7": -1800000000, "min_lat_e7": 850000000, "max_lon_e7": -1799000000,
                    "max_lat_e7": 850511287, "center_zoom": 15, "center_lon_e7": 0, "center_lat_e7": 0},
                   {"name": "u5_synthetic", "fixture": "TA-001B U5"})
    with open(os.path.join(out_dir, "u5_expected_synthetic.tsv"), "w", encoding="ascii", newline="\n") as f:
        f.write("tile_id\tz\tx\ty\trgb_sha256\tnodata_px\tmin_code\tmax_code\n")
        for tid in sorted(tiles):
            x, rgb = tiles[tid]
            codes = terrainrgb.rgb_to_codes(rgb)
            f.write(f"{tid}\t15\t{x}\t0\t{hashlib.sha256(rgb.tobytes()).hexdigest()}\t"
                    f"{int((codes == terrainrgb.NODATA_CODE).sum())}\t{int(codes.min())}\t{int(codes.max())}\n")
    log(f"u5 synthetic: {len(tiles)} tiles -> {path}")


def expect_archive(path: str, out_json: str, log=print) -> None:
    """Reference-reader expectations for a production archive (all tiles)."""
    exp = expectations(path, [])
    exp["archive_path"] = os.path.abspath(path)
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(exp, f, indent=0)
    log(f"expected {os.path.basename(path)}: {len(exp['tiles'])} tiles")
