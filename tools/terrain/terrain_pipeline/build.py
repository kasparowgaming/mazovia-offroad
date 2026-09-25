"""Stage 2: sources -> EPSG:3857 XYZ Terrain-RGB PNG tiles -> PMTiles v3 + build manifest (DESIGN §7.7, §8.4).

Per corridor region:
  1. exact 1 m mosaic in the sheets' native CRS (all selected sheets share EPSG:2180 and an integer-centred 1 m grid,
     asserted); priority = selection order (newest first), first valid value wins; no resampling;
  2. ONE GDAL warp of the mosaic to the zoom-z XYZ pixel grid (EPSG:3857, 256 px tiles), resampling = average
     (area-weighted mean of the valid 1 m cells inside each target pixel), exact transformer (tolerance 0), 1 thread;
  3. per tile: Terrain-RGB mapbox codes (0.1 m, round-half-even), nodata = 0xFFFFFF, all-nodata tiles omitted;
  4. PNG via Pillow (RGB 8-bit, compress_level 9, no ancillary colour chunks);
  5. PMTiles v3 via the reference `pmtiles` Python writer (tile type PNG, tile compression none, internal gzip),
     tiles written in ascending tile-id order.
"""

from __future__ import annotations

import datetime
import hashlib
import io
import json
import os
import platform
import subprocess
import sys
import zlib

import numpy as np
import rasterio
from PIL import Image
from rasterio.transform import Affine
from rasterio.warp import Resampling, reproject

from . import PIPELINE_VERSION, regions, terrainrgb, tiling

SCHEMA_VERSION = "ta001b-build-manifest-1"
ATTRIBUTION = "Dane wysokościowe: GUGiK (NMT) — Główny Urząd Geodezji i Kartografii, geoportal.gov.pl"
RESAMPLING = "average"
SRC_NODATA = -9999.0


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pipeline_hashes() -> dict:
    base = os.path.dirname(os.path.abspath(__file__))
    files = sorted(f for f in os.listdir(base) if f.endswith(".py"))
    out = {f"terrain_pipeline/{f}": sha256_file(os.path.join(base, f)) for f in files}
    top = os.path.join(os.path.dirname(base), "build_terrain.py")
    out["build_terrain.py"] = sha256_file(top)
    return out


def tool_versions() -> dict:
    import numpy
    import PIL
    import pmtiles  # noqa: F401
    import pyproj
    from importlib import metadata as md
    return {
        "python": sys.version.split()[0],
        "platform": platform.platform(),
        "numpy": numpy.__version__,
        "rasterio": rasterio.__version__,
        "gdal": rasterio.__gdal_version__,
        "pyproj": pyproj.__version__,
        "proj": pyproj.proj_version_str,
        "pillow": PIL.__version__,
        "zlib": zlib.ZLIB_RUNTIME_VERSION,
        "pmtiles_python_writer": md.version("pmtiles"),
    }


def git_head(repo: str) -> tuple[str, bool]:
    try:
        head = subprocess.check_output(["git", "-C", repo, "rev-parse", "HEAD"], text=True).strip()
        dirty = bool(subprocess.check_output(["git", "-C", repo, "status", "--porcelain"], text=True).strip())
        return head, dirty
    except Exception:  # noqa: BLE001
        return "unknown", True


def encode_png(rgb: np.ndarray) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(rgb, "RGB").save(buf, format="PNG", compress_level=9)
    return buf.getvalue()


def _open_sheet(path: str):
    ds = rasterio.open(path)
    t = ds.transform
    if str(ds.crs) != "EPSG:2180" or t.a != 1.0 or t.e != -1.0 or t.b != 0 or t.d != 0 \
            or (t.c % 1.0) != 0.5 or (t.f % 1.0) != 0.5:
        raise RuntimeError(f"{path}: unexpected grid {ds.crs} {t}")
    return ds


def region_mosaic(sheet_paths: list[str], bounds: tuple[float, float, float, float]):
    """Exact mosaic on the shared 1 m grid; bounds are snapped outward to cell edges (k + 0.5)."""
    minx = np.floor(bounds[0] - 0.5) + 0.5
    maxy = np.ceil(bounds[3] - 0.5) + 0.5
    maxx = np.ceil(bounds[2] - 0.5) + 0.5
    miny = np.floor(bounds[1] - 0.5) + 0.5
    w, h = int(maxx - minx), int(maxy - miny)
    mosaic = np.full((h, w), np.nan, dtype=np.float32)
    for p in sheet_paths:  # priority order: first valid wins
        with _open_sheet(p) as ds:
            t = ds.transform
            col0 = int(round(t.c - minx))
            row0 = int(round(maxy - t.f))
            r0, r1 = max(0, row0), min(h, row0 + ds.height)
            c0, c1 = max(0, col0), min(w, col0 + ds.width)
            if r0 >= r1 or c0 >= c1:
                continue
            a = ds.read(1, window=((r0 - row0, r1 - row0), (c0 - col0, c1 - col0)))
            a = np.where(a == ds.nodata, np.nan, a).astype(np.float32)
            dst = mosaic[r0:r1, c0:c1]
            fill = np.isnan(dst) & ~np.isnan(a)
            dst[fill] = a[fill]
    return mosaic, Affine(1.0, 0.0, float(minx), 0.0, -1.0, float(maxy))


def render_region(sheet_paths: list[str], z: int, tiles: list[tuple[int, int]]):
    """Returns {(x, y): heights float64 256x256 with NaN} for the given tiles."""
    from pyproj import Transformer
    xs = [t[0] for t in tiles]
    ys = [t[1] for t in tiles]
    tx0, tx1, ty0, ty1 = min(xs), max(xs), min(ys), max(ys)
    res = tiling.resolution_m(z)
    minx, _, _, maxy = tiling.tile_bounds_3857(z, tx0, ty0)
    width, height = (tx1 - tx0 + 1) * tiling.TILE_SIZE, (ty1 - ty0 + 1) * tiling.TILE_SIZE
    dst_transform = Affine(res, 0.0, minx, 0.0, -res, maxy)
    # source window: destination footprint in EPSG:2180 (densified edges) + 20 m margin
    to2180 = Transformer.from_crs("EPSG:3857", "EPSG:2180", always_xy=True)
    edge = np.linspace(0.0, 1.0, 65)
    bx = np.concatenate([minx + edge * width * res, np.full(65, minx + width * res), minx + edge * width * res, np.full(65, minx)])
    by = np.concatenate([np.full(65, maxy), maxy - edge * height * res, np.full(65, maxy - height * res), maxy - edge * height * res])
    ex, ny = to2180.transform(bx, by)
    mosaic, src_transform = region_mosaic(sheet_paths, (min(ex) - 20, min(ny) - 20, max(ex) + 20, max(ny) + 20))
    src = np.where(np.isnan(mosaic), SRC_NODATA, mosaic).astype(np.float32)
    dst = np.full((height, width), SRC_NODATA, dtype=np.float32)
    reproject(source=src, destination=dst, src_transform=src_transform, src_crs="EPSG:2180", src_nodata=SRC_NODATA,
              dst_transform=dst_transform, dst_crs="EPSG:3857", dst_nodata=SRC_NODATA,
              resampling=Resampling.average, num_threads=1, tolerance=0.0)
    out = {}
    for x, y in tiles:
        r0 = (y - ty0) * tiling.TILE_SIZE
        c0 = (x - tx0) * tiling.TILE_SIZE
        a = dst[r0:r0 + tiling.TILE_SIZE, c0:c0 + tiling.TILE_SIZE].astype(np.float64)
        a[a == SRC_NODATA] = np.nan
        out[(x, y)] = a
    return out


def e7_floor(v: float) -> int:
    return int(np.floor(v * 1e7))


def e7_ceil(v: float) -> int:
    return int(np.ceil(v * 1e7))


def build_archive(decl: dict, work: str, out_dir: str, z: int, repo: str, argv: str, primary: bool, log=print) -> dict:
    from pmtiles.tile import Compression, TileType, zxy_to_tileid
    from pmtiles.writer import Writer

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(work, "sources.json"), encoding="utf-8") as f:
        sources = json.load(f)
    half = decl["corridor_half_width_m"]
    tiles_out: dict[int, tuple[tuple[int, int, int], bytes, np.ndarray]] = {}
    per_region = []
    input_records = []
    for region in sources["regions"]:
        t = next(tt for tt in decl["transects"] if tt["id"] == region["transect_id"])
        tiles = regions.corridor_tiles(t, half, z)
        paths = [os.path.join(work, "sources", s["akt_rok"], os.path.basename(s["url_do_pobrania"])) for s in region["sheets"]]
        for s, p in zip(region["sheets"], paths):
            if sha256_file(p) != s["sha256"]:
                raise RuntimeError(f"source checksum mismatch: {p}")
        heights = render_region(paths, z, tiles)
        written = nodata_px = 0
        for (x, y), h in sorted(heights.items()):
            codes = terrainrgb.encode_codes(h)
            nd = int((codes == terrainrgb.NODATA_CODE).sum())
            if nd == codes.size:
                continue
            rgb = terrainrgb.codes_to_rgb(codes)
            tid = zxy_to_tileid(z, x, y)
            if tid in tiles_out:
                raise RuntimeError(f"tile {z}/{x}/{y} produced by two regions")
            tiles_out[tid] = ((z, x, y), encode_png(rgb), rgb)
            written += 1
            nodata_px += nd
        per_region.append({"transect_id": t["id"], "corridor_tiles": len(tiles), "tiles_written": written,
                           "nodata_pixels": nodata_px, "source_sheets": [s["godlo"] for s in region["sheets"]]})
        for s in region["sheets"]:
            rec = {k: v for k, v in s.items() if not k.startswith("_")}
            rec["local_relpath"] = f"sources/{s['akt_rok']}/{os.path.basename(s['url_do_pobrania'])}"
            input_records.append(rec)
        log(f"z{z} {t['id']}: {written}/{len(tiles)} tiles, nodata px {nodata_px}")

    ids = sorted(tiles_out)
    bbox = [180.0, 90.0, -180.0, -90.0]
    for tid in ids:
        (zz, x, y), _, _ = tiles_out[tid]
        w, s, e, n = tiling.tile_bounds_lonlat(zz, x, y)
        bbox = [min(bbox[0], w), min(bbox[1], s), max(bbox[2], e), max(bbox[3], n)]
    years = sorted({r["akt_rok"] for r in input_records})
    tool = tool_versions()
    identity = {
        "schema_version": SCHEMA_VERSION,
        "pipeline_version": PIPELINE_VERSION,
        "pipeline_sha256": pipeline_hashes(),
        "validation_set_sha256": sha256_file(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                                          "validation", "validation_routes.json")),
        "validation_corridor_ids": [t["id"] for t in decl["transects"]],
        "corridor_half_width_m": half,
        "source": {"dataset": "GUGiK NMT", "selection_rule": sources["selection_rule"], "wfs_index": sources["wfs_url"],
                   "vertical_datum": "PL-EVRF2007-NH", "horizontal_crs_inputs": sorted({r["crs"] for r in input_records}),
                   "years": years, "inputs": input_records},
        "raster": {"grid": "EPSG:3857 XYZ", "zoom": z, "tile_size_px": tiling.TILE_SIZE, "resampling": RESAMPLING,
                   "resampling_note": "GDAL warp average (area-weighted mean of valid source cells), exact transformer, "
                                      "1 thread; mosaic exact on the shared 1 m grid, newest sheet wins",
                   "pixel_convention": "value at pixel centre (i+0.5, j+0.5)"},
        "encoding": {"name": terrainrgb.ENCODING, "formula": terrainrgb.FORMULA, "quantisation_m": 0.1,
                     "rounding": "round half to even on (h + 10000) * 10",
                     "nodata_code": terrainrgb.NODATA_CODE, "nodata_rgb": list(terrainrgb.NODATA_RGB),
                     "valid_height_range_m": [terrainrgb.MIN_VALID_M, terrainrgb.MAX_VALID_M],
                     "png": "8-bit RGB, no alpha, Pillow compress_level 9, no gAMA/sRGB/iCCP/cHRM chunks",
                     "all_nodata_tiles": "omitted"},
        "pmtiles": {"version": 3, "tile_type": "png", "tile_compression": "none", "internal_compression": "gzip",
                    "writer": f"pmtiles (Python) {tool['pmtiles_python_writer']}", "pythonhashseed": "0"},
        "tools": {k: v for k, v in tool.items() if k != "platform"},
        "attribution": ATTRIBUTION,
    }
    build_id = "ta001b-z%d-%s" % (z, hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:16])
    metadata = {
        "name": f"Mazovia Terrain-RGB z{z} ({build_id})",
        "description": "GUGiK NMT 1 m (PL-EVRF2007-NH) resampled to Web Mercator XYZ, Terrain-RGB mapbox encoding",
        "attribution": ATTRIBUTION,
        "type": "baselayer",
        "version": "1.0.0",
        "encoding": "mapbox",
        "mazovia_terrain": {
            "schema": "mazovia-terrain-archive-1",
            "build_id": build_id,
            "source": "GUGiK NMT",
            "vertical_datum": "PL-EVRF2007-NH",
            "horizontal_grid": "EPSG:3857 XYZ",
            "zoom": z,
            "tile_size": tiling.TILE_SIZE,
            "encoding": "mapbox",
            "quantisation_m": 0.1,
            "nodata_rgb": list(terrainrgb.NODATA_RGB),
            "source_years": [int(years[0]), int(years[-1])],
            "resampling": RESAMPLING,
            "pipeline_version": PIPELINE_VERSION,
        },
    }
    name = f"terrain_z{z}.pmtiles"
    path = os.path.join(out_dir, name)
    with open(path, "wb") as f:
        w = Writer(f)
        for tid in ids:
            w.write_tile(tid, tiles_out[tid][1])
        cz = z
        w.finalize({
            "tile_type": TileType.PNG, "tile_compression": Compression.NONE,
            "min_lon_e7": e7_floor(bbox[0]), "min_lat_e7": e7_floor(bbox[1]),
            "max_lon_e7": e7_ceil(bbox[2]), "max_lat_e7": e7_ceil(bbox[3]),
            "center_zoom": cz, "center_lon_e7": int(round((bbox[0] + bbox[2]) / 2 * 1e7)),
            "center_lat_e7": int(round((bbox[1] + bbox[3]) / 2 * 1e7)),
        }, metadata)
    # U5 expectation: raw RGB (as encoded) per tile, for the on-device BitmapFactory bit-exact test
    with open(os.path.join(out_dir, f"u5_expected_z{z}.tsv"), "w", encoding="ascii", newline="\n") as f:
        f.write("tile_id\tz\tx\ty\trgb_sha256\tnodata_px\tmin_code\tmax_code\n")
        for tid in ids:
            (zz, x, y), _, rgb = tiles_out[tid]
            codes = terrainrgb.rgb_to_codes(rgb)
            valid = codes[codes != terrainrgb.NODATA_CODE]
            f.write(f"{tid}\t{zz}\t{x}\t{y}\t{hashlib.sha256(rgb.tobytes()).hexdigest()}\t"
                    f"{int((codes == terrainrgb.NODATA_CODE).sum())}\t{int(valid.min()) if valid.size else -1}\t"
                    f"{int(valid.max()) if valid.size else -1}\n")
    png_bytes = sum(len(tiles_out[t][1]) for t in ids)
    head, dirty = git_head(repo)
    manifest = {
        "build_id": build_id,
        "identity": identity,
        "output": {
            "archive": name, "sha256": sha256_file(path), "bytes": os.path.getsize(path), "tile_count": len(ids),
            "png_payload_bytes": png_bytes, "raw_rgb_bytes": len(ids) * tiling.TILE_SIZE ** 2 * 3,
            "bbox_wgs84": [round(v, 7) for v in bbox], "min_zoom": z, "max_zoom": z,
            "per_corridor": per_region, "u5_expected": f"u5_expected_z{z}.tsv",
        },
        "run": {
            "build_timestamp": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
            "repository_head": head, "repository_dirty": dirty,
            "command": f"PYTHONHASHSEED=0 python tools/terrain/{argv}",
            "platform": tool["platform"],
            "note": "run section is not part of reproducibility identity",
        },
    }
    with open(os.path.join(out_dir, f"build_manifest_z{z}.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    log(f"z{z}: {len(ids)} tiles, {manifest['output']['bytes']} B, sha256 {manifest['output']['sha256']}")
    return manifest
