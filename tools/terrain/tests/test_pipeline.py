"""Tooling unit tests (no network, no source data):  python -m pytest tools/terrain/tests -q"""

import io
import json
import math
import os
import sys
import zlib

import numpy as np
import pytest
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)

from terrain_pipeline import gugik, probe, regions, terrainrgb, tiling, validation_set  # noqa: E402
from terrain_pipeline.build import encode_png  # noqa: E402


# ---------------------------------------------------------------- Terrain-RGB

def test_known_vectors():
    assert terrainrgb.encode_height(0.0) == (1, 134, 160)
    assert terrainrgb.encode_height(100.0) == (1, 138, 136)
    assert terrainrgb.encode_height(123.4) == (1, 139, 114)
    assert terrainrgb.decode_rgb(1, 139, 114) == pytest.approx(123.4, abs=1e-9)


def test_round_trip_minus100_to_2000_every_decimetre():
    k = np.arange(-1000, 20001)
    h = k / 10.0
    codes = terrainrgb.encode_codes(h)
    assert (codes == k + 100000).all()
    back = terrainrgb.decode_codes(terrainrgb.rgb_to_codes(terrainrgb.codes_to_rgb(codes)))
    assert np.allclose(back, h, atol=1e-9, rtol=0)


def test_nodata_is_reserved_never_zero():
    codes = terrainrgb.encode_codes(np.array([np.nan, 0.0]))
    assert codes[0] == terrainrgb.NODATA_CODE and codes[1] == 100000
    assert terrainrgb.encode_height(None) == terrainrgb.NODATA_RGB == (255, 255, 255)
    assert terrainrgb.decode_rgb(255, 255, 255) is None
    assert math.isnan(terrainrgb.decode_codes(np.array([terrainrgb.NODATA_CODE]))[0])


def test_out_of_range_is_refused_not_clamped():
    with pytest.raises(terrainrgb.EncodingError):
        terrainrgb.encode_codes(np.array([9000.0]))
    with pytest.raises(terrainrgb.EncodingError):
        terrainrgb.encode_codes(np.array([-500.1]))


def test_png_has_no_colour_chunks_and_round_trips():
    rgb = terrainrgb.codes_to_rgb(terrainrgb.encode_codes(np.linspace(80, 120.3, 256 * 256).reshape(256, 256).round(1)))
    png = encode_png(rgb)
    types, p = [], 8
    while p < len(png):
        n = int.from_bytes(png[p:p + 4], "big")
        types.append(png[p + 4:p + 8].decode())
        p += 12 + n
    assert types[0] == "IHDR" and types[-1] == "IEND"
    assert not set(types) & {"gAMA", "cHRM", "sRGB", "iCCP", "PLTE", "tRNS"}
    assert png[25] == 2  # colour type truecolour, no alpha
    assert (np.asarray(Image.open(io.BytesIO(png))) == rgb).all()


# ---------------------------------------------------------------- addressing

def test_tile_math_matches_reference_tile_ids():
    from pmtiles.tile import zxy_to_tileid
    assert zxy_to_tileid(12, 3423, 1763) == 19078479
    x, y = tiling.lonlat_to_tile(21.0, 52.2, 15)
    w, s, e, n = tiling.tile_bounds_lonlat(15, x, y)
    assert w <= 21.0 < e and s <= 52.2 < n
    assert tiling.lonlat_to_tile(-180.0, 85.0, 0) == (0, 0)


def test_world_pixel_round_trip_and_ground_spacing():
    for lon, lat in [(21.0, 52.2), (19.7, 52.55), (20.8, 51.23)]:
        px, py = tiling.lonlat_to_world_px(lon, lat, 15)
        lon2, lat2 = tiling.world_px_to_lonlat(px, py, 15)
        assert lon2 == pytest.approx(lon, abs=1e-10) and lat2 == pytest.approx(lat, abs=1e-10)
    assert tiling.ground_spacing_m(15, 52.25) == pytest.approx(2.93, abs=0.01)
    assert tiling.ground_spacing_m(14, 52.25) == pytest.approx(5.85, abs=0.01)


def test_tile_bounds_3857_are_contiguous():
    a = tiling.tile_bounds_3857(15, 100, 200)
    b = tiling.tile_bounds_3857(15, 101, 200)
    assert a[2] == pytest.approx(b[0]) and a[3] - a[1] == pytest.approx(256 * tiling.resolution_m(15))


# ---------------------------------------------------------------- sheet selection / provenance

def _sheet(godlo, date, ring, **kw):
    base = dict(layer="gugik:SkorowidzNMT2025", feature_id="x", godlo=godlo, akt_rok=date[:4], akt_data=date, asortyment="NMT",
                format="ARC/INFO ASCII GRID", char_przestrz="1.00 m", blad_sr_wys="0.10", blad_sr_syt="0.10", uklad_xy="PL-1992",
                modul_archiwizacji="1:5000", uklad_h="PL-EVRF2007-NH", nr_zglosz="n", czy_ark_wypelniony="TAK",
                url_do_pobrania=f"https://opendata.geoportal.gov.pl/NumDaneWys/NMT/1/1_{godlo}.asc", zrodlo_danych="Skaning laserowy",
                ring_2180=ring)
    base.update(kw)
    return gugik.Sheet(**base)


def test_selection_rule_filters_non_als_and_other_datums():
    good = _sheet("A", "2025-01-01", [])
    assert good.selectable() and good.crs == "EPSG:2180"
    assert not _sheet("B", "2025-01-01", [], zrodlo_danych="Zdj. lotnicze").selectable()
    assert not _sheet("C", "2025-01-01", [], uklad_h="PL-KRON86-NH").selectable()
    assert not _sheet("D", "2025-01-01", [], char_przestrz="5.00 m").selectable()


def test_greedy_selection_prefers_newest_and_skips_redundant():
    sq = [(0, 0), (10, 0), (10, 10), (0, 10)]
    new, old = _sheet("N", "2025-04-01", sq), _sheet("O", "2019-04-01", sq)
    half = _sheet("H", "2019-01-01", [(10, 0), (20, 0), (20, 10), (10, 10)])
    e, n = np.meshgrid(np.arange(0.5, 20, 1.0), np.arange(0.5, 10, 1.0))
    chosen, uncovered, total = regions.select_sheets([old, half, new], e.ravel(), n.ravel())
    assert [s.godlo for s in chosen] == ["N", "H"] and uncovered == 0 and total == 200


def test_provenance_record_has_required_fields():
    rec = gugik.provenance_record(_sheet("A", "2025-01-01", []))
    for k in ("godlo", "akt_data", "url_do_pobrania", "uklad_xy", "crs", "uklad_h", "zrodlo_danych", "blad_sr_wys"):
        assert rec[k]


def test_manifest_schema_accepts_committed_validation_manifest():
    import jsonschema
    schema = json.load(open(os.path.join(ROOT, "manifest.schema.json"), encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    path = os.path.join(ROOT, "validation", "build_manifest.json")
    if not os.path.exists(path):
        pytest.skip("no validation build manifest yet")
    doc = json.load(open(path, encoding="utf-8"))
    jsonschema.validate(doc, schema)
    for b in doc["builds"]:
        assert b["identity"]["source"]["vertical_datum"] == "PL-EVRF2007-NH"
        assert {i["uklad_h"] for i in b["identity"]["source"]["inputs"]} == {"PL-EVRF2007-NH"}
    bad = json.loads(json.dumps(doc))
    bad["builds"][0]["identity"]["source"]["inputs"][0]["uklad_h"] = "PL-KRON86-NH"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(bad, schema)


# ---------------------------------------------------------------- frozen validation set

def test_validation_set_is_frozen_and_deterministic():
    decl = validation_set.load(os.path.join(ROOT, "validation", "validation_routes.json"))
    pts = validation_set.verify_frozen(decl)
    assert len(pts) == decl["point_sample"]["count"] == 10000
    assert len(decl["transects"]) >= 5 and all(t["type"] == "TRANSECT" for t in decl["transects"])
    assert validation_set.points_sha256(validation_set.generate_points(decl)) == decl["point_sample"]["csv_sha256"]


def test_points_lie_inside_their_corridor():
    decl = validation_set.load(os.path.join(ROOT, "validation", "validation_routes.json"))
    half = decl["corridor_half_width_m"]
    by_id = {t["id"]: t for t in decl["transects"]}
    for _, tid, lat, lon in validation_set.generate_points(decl)[::97]:
        t = by_id[tid]
        (la0, lo0), (la1, lo1) = t["start"], t["end"]
        m_lat = 6371000.0 * math.pi / 180
        m_lon = m_lat * math.cos(math.radians((la0 + la1) / 2))
        dx, dy = (lo1 - lo0) * m_lon, (la1 - la0) * m_lat
        px, py = (lon - lo0) * m_lon, (lat - la0) * m_lat
        L = math.hypot(dx, dy)
        assert abs((px * dy - py * dx) / L) <= half + 1e-6
        assert -1e-6 <= (px * dx + py * dy) / L <= L + 1e-6


# ---------------------------------------------------------------- size probe

def test_probe_payload_is_lossless():
    rng = np.random.default_rng(3)
    codes = (100000 + rng.integers(0, 3000, size=(256, 256))).astype(np.uint32)
    codes[5, 7] = terrainrgb.NODATA_CODE
    zz = np.frombuffer(probe._tile_payload(codes), dtype="<u2").astype(np.int64).reshape(256, 256)
    d = (zz >> 1) ^ -(zz & 1)
    v = np.zeros_like(d)
    for r in range(256):
        v[r, 0] = ((v[r - 1, 0] if r else 0) + d[r, 0]) & 0xFFFF
        for c in range(1, 256):
            v[r, c] = (v[r, c - 1] + d[r, c]) & 0xFFFF
    valid = codes != terrainrgb.NODATA_CODE
    base = int(codes[valid].min())
    assert (v[~valid] == 0xFFFF).all()
    assert (v[valid] + base == codes[valid]).all()
    assert len(zlib.compress(probe._tile_payload(codes))) > 0
