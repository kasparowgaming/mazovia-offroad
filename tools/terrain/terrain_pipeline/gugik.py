"""GUGiK NMT sheet discovery, selection and download with provenance (DESIGN §7.1, §7.7).

Source of truth: the official GUGiK WFS sheet index for NMT in PL-EVRF2007-NH, one layer per data year
(`gugik:SkorowidzNMT<year>`). Each feature carries the sheet id (godlo), date, format, grid spacing, vertical RMSE,
horizontal CRS, vertical datum, data source and the official download URL on opendata.geoportal.gov.pl.

Selection rule (declared in TA-001B before any result was computed, see validation/validation_routes.json):
  asortyment == NMT, char_przestrz == 1.00 m, uklad_h == PL-EVRF2007-NH, zrodlo_danych == Skaning laserowy (ALS),
  czy_ark_wypelniony == TAK, format == ARC/INFO ASCII GRID;
  priority = newest akt_data first, then godlo (lexicographic) for determinism;
  a lower-priority sheet is skipped when the region part it would cover is already covered by higher-priority sheets.
Mixing vertical datums is impossible by construction (EVRF2007-only index) and is re-checked.
"""

from __future__ import annotations

import hashlib
import json
import os
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, asdict, field

WFS_URL = "https://mapy.geoportal.gov.pl/wss/service/PZGIK/NumerycznyModelTerenuEVRF2007/WFS/Skorowidze"
YEARS = tuple(range(2018, 2027))
NS = {"gugik": "http://www.gugik.gov.pl", "gml": "http://www.opengis.net/gml/3.2", "wfs": "http://www.opengis.net/wfs/2.0"}
FIELDS = ["godlo", "akt_rok", "asortyment", "format", "char_przestrz", "blad_sr_wys", "blad_sr_syt", "uklad_xy",
          "modul_archiwizacji", "uklad_h", "nr_zglosz", "czy_ark_wypelniony", "url_do_pobrania", "zrodlo_danych"]

CRS_BY_UKLAD = {"PL-1992": "EPSG:2180", "PL-2000:S5": "EPSG:2176", "PL-2000:S6": "EPSG:2177",
                "PL-2000:S7": "EPSG:2178", "PL-2000:S8": "EPSG:2179"}

SELECTION_RULE = {
    "asortyment": "NMT",
    "char_przestrz": "1.00 m",
    "uklad_h": "PL-EVRF2007-NH",
    "zrodlo_danych": "Skaning laserowy",
    "czy_ark_wypelniony": "TAK",
    "format": "ARC/INFO ASCII GRID",
    "priority": "akt_data descending, then godlo ascending; skip sheets whose region part is already covered",
}
USER_AGENT = "MazoviaOffroad-TerrainPipeline/TA-001B (offline DEM preprocessing)"


@dataclass
class Sheet:
    layer: str
    feature_id: str
    godlo: str
    akt_rok: str
    akt_data: str
    asortyment: str
    format: str
    char_przestrz: str
    blad_sr_wys: str
    blad_sr_syt: str
    uklad_xy: str
    modul_archiwizacji: str
    uklad_h: str
    nr_zglosz: str
    czy_ark_wypelniony: str
    url_do_pobrania: str
    zrodlo_danych: str
    # exterior ring in the WFS CRS EPSG:2180 as (easting, northing)
    ring_2180: list = field(default_factory=list)

    @property
    def crs(self) -> str:
        return CRS_BY_UKLAD[self.uklad_xy]

    @property
    def file_name(self) -> str:
        return os.path.basename(urllib.parse.urlparse(self.url_do_pobrania).path)

    def selectable(self) -> bool:
        return all(getattr(self, k) == v for k, v in SELECTION_RULE.items() if k != "priority") \
            and self.uklad_xy in CRS_BY_UKLAD

    def priority_key(self):
        return (_neg_date(self.akt_data), self.godlo)


def _neg_date(d: str) -> str:
    # descending date as an ascending sort key
    return "".join(chr(0x7F - ord(c)) for c in d)


def _http_get(url: str, timeout: float = 120.0, retries: int = 4) -> bytes:
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - network errors are retried then re-raised
            last = e
            time.sleep(2.0 * (attempt + 1))
    raise RuntimeError(f"GET failed after {retries} attempts: {url}: {last}")


def query_layer(year: int, south: float, west: float, north: float, east: float) -> list[Sheet]:
    layer = f"gugik:SkorowidzNMT{year}"
    q = dict(SERVICE="WFS", VERSION="2.0.0", REQUEST="GetFeature", TYPENAMES=layer, COUNT="1000",
             BBOX=f"{south},{west},{north},{east},urn:ogc:def:crs:EPSG::4326")
    root = ET.fromstring(_http_get(WFS_URL + "?" + urllib.parse.urlencode(q)))
    out = []
    for m in root.findall("wfs:member", NS):
        feat = list(m)[0]
        vals = {k: (feat.findtext(f"gugik:{k}", default="", namespaces=NS) or "").strip() for k in FIELDS}
        date = (feat.findtext("gugik:akt_data/gml:timePosition", default="", namespaces=NS) or "").strip()
        pos = feat.find(".//gml:exterior//gml:posList", NS)
        ring = []
        if pos is not None and pos.text:
            nums = [float(v) for v in pos.text.split()]
            # urn:ogc:def:crs:EPSG::2180 axis order is northing, easting
            ring = [(nums[i + 1], nums[i]) for i in range(0, len(nums) - 1, 2)]
        out.append(Sheet(layer=layer, feature_id=feat.get("{http://www.opengis.net/gml/3.2}id", ""),
                         akt_data=date, ring_2180=ring, **vals))
    return out


def discover(south: float, west: float, north: float, east: float, years=YEARS) -> list[Sheet]:
    sheets: list[Sheet] = []
    for y in years:
        sheets.extend(query_layer(y, south, west, north, east))
    return sheets


def download(sheet: Sheet, dest_dir: str) -> dict:
    """Downloads once into dest_dir/<year>/<file>; returns a provenance record with SHA-256."""
    sub = os.path.join(dest_dir, sheet.akt_rok)
    os.makedirs(sub, exist_ok=True)
    path = os.path.join(sub, sheet.file_name)
    if not os.path.exists(path):
        data = _http_get(sheet.url_do_pobrania, timeout=600.0)
        tmp = path + ".part"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    rec = provenance_record(sheet)
    rec.update(sha256=h.hexdigest(), bytes=os.path.getsize(path))
    return rec | {"_local_path": path}


def provenance_record(sheet: Sheet) -> dict:
    d = asdict(sheet)
    d.pop("ring_2180")
    d["crs"] = sheet.crs
    return d


def write_prj(asc_path: str, epsg: str) -> None:
    """ASC sheets carry no CRS; the CRS comes from the official index (uklad_xy) and is written as a sidecar."""
    from pyproj import CRS
    prj = os.path.splitext(asc_path)[0] + ".prj"
    wkt = CRS.from_user_input(epsg).to_wkt(version="WKT1_ESRI")
    with open(prj, "w", encoding="ascii") as f:
        f.write(wkt)


def dump_index(sheets: list[Sheet], path: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump([asdict(s) for s in sheets], f, ensure_ascii=False, indent=1)
