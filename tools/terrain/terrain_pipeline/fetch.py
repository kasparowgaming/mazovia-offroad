"""Stage 1: discover + select + download official GUGiK NMT sheets for each validation corridor."""

from __future__ import annotations

import json
import os

from pyproj import Transformer

from . import gugik, regions


def fetch(decl: dict, work_dir: str, zoom: int, log=print) -> dict:
    src_dir = os.path.join(work_dir, "sources")
    os.makedirs(src_dir, exist_ok=True)
    to2180 = Transformer.from_crs("EPSG:4326", "EPSG:2180", always_xy=True)
    half = decl["corridor_half_width_m"]
    result = {"selection_rule": gugik.SELECTION_RULE, "wfs_url": gugik.WFS_URL, "regions": []}
    for t in decl["transects"]:
        tiles = regions.corridor_tiles(t, half, zoom)
        lon, lat = regions.tiles_lattice_lonlat(zoom, tiles)
        e, n = to2180.transform(lon, lat)
        south, north = float(lat.min()) - 0.001, float(lat.max()) + 0.001
        west, east = float(lon.min()) - 0.002, float(lon.max()) + 0.002
        index_path = os.path.join(src_dir, f"wfs_index_{t['id']}.json")
        sheets = gugik.discover(south, west, north, east)
        gugik.dump_index(sheets, index_path)
        candidates = [s for s in sheets if s.selectable()]
        chosen, uncovered, total = regions.select_sheets(candidates, e, n)
        datums = {s.uklad_h for s in chosen}
        if len(datums) > 1:
            raise RuntimeError(f"{t['id']}: mixed vertical datums {datums}")
        log(f"{t['id']}: {len(tiles)} z{zoom} tiles, {len(sheets)} indexed sheets, {len(candidates)} selectable, "
            f"{len(chosen)} chosen, uncovered lattice {uncovered}/{total}")
        recs = []
        for s in chosen:
            rec = gugik.download(s, src_dir)
            gugik.write_prj(rec["_local_path"], s.crs)
            log(f"   {s.godlo} {s.akt_data} {s.uklad_xy} {rec['bytes']} B sha256 {rec['sha256'][:16]}")
            recs.append(rec)
        result["regions"].append({
            "transect_id": t["id"], "zoom_for_selection": zoom, "tiles": len(tiles),
            "indexed_sheets": len(sheets), "selectable_sheets": len(candidates),
            "uncovered_lattice_points": uncovered, "lattice_points": total,
            "wfs_query_bbox_wgs84": [south, west, north, east], "sheets": recs,
        })
    with open(os.path.join(work_dir, "sources.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    return result
