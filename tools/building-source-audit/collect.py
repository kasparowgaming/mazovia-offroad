"""Fetch a bounded public building sample; preserve requests and raw evidence."""
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'.tmp-building-source-audit/packages'))
from pyproj import Transformer
from shapely.geometry import LineString, MultiLineString, mapping
from shapely.ops import transform
OUT=ROOT/'docs/terrain-ahead/building-source-audit/2026-09-27-siedlce'
OUT.mkdir(parents=True,exist_ok=True)

def fetch(name,url,data=None,timeout=60):
    file=OUT/name
    if file.exists(): return file.read_bytes()
    started=datetime.now(timezone.utc).isoformat()
    req=urllib.request.Request(url,data=data,headers={'User-Agent':'MazoviaOffroad-BuildingSourceAudit/1.0','Content-Type':'application/x-www-form-urlencoded'})
    record={'url':url,'request_body':data.decode() if data else None,'retrieved_at':started}
    try:
        with urllib.request.urlopen(req,timeout=timeout) as r:
            payload=r.read();record.update(status=r.status,content_type=r.headers.get('Content-Type'))
        file.write_bytes(payload); record.update(bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest())
        (OUT/(name+'.provenance.json')).write_text(json.dumps(record,indent=2),encoding='utf-8')
        return payload
    except Exception as e:
        record.update(error=str(e));(OUT/(name+'.error.json')).write_text(json.dumps(record,indent=2),encoding='utf-8');raise

def query(base,params): return base+'?'+urllib.parse.urlencode(params)

def area():
    xml=ET.parse(ROOT/'test_mazovia.gpx')
    segments=[[(float(p.attrib['lon']),float(p.attrib['lat'])) for p in s.findall('trkpt')] for s in xml.findall('.//trkseg')]
    route=MultiLineString(segments)
    forward=Transformer.from_crs(4326,2180,always_xy=True).transform
    backward=Transformer.from_crs(2180,4326,always_xy=True).transform
    metric=transform(forward,route); corridor=metric.buffer(200)
    geographic=transform(backward,corridor)
    merc=transform(Transformer.from_crs(2180,3857,always_xy=True).transform,corridor)
    specification={'source':'test_mazovia.gpx','kind':'synthetic test GPX, not a verified navigable route','segment_count':len(segments),'length_m':metric.length,'half_width_m':200,'area_m2':corridor.area,'bounds_wgs84':geographic.bounds,'bounds_3857':merc.bounds,'route_wgs84':mapping(route),'corridor_wgs84':mapping(geographic),'corridor_2180':mapping(corridor)}
    (OUT/'area.json').write_text(json.dumps(specification,indent=2),encoding='utf-8')
    return specification

if __name__=='__main__':
    spec=area(); print('AREA',json.dumps({k:v for k,v in spec.items() if not isinstance(v,dict)}))
    west,south,east,north=spec['bounds_wgs84']
    osm=f'[out:json][timeout:50];(way["building"]({south},{west},{north},{east});relation["building"]["type"="multipolygon"]({south},{west},{north},{east}););out meta geom;'
    tasks=[
        ('osm.json','https://overpass-api.de/api/interpreter',urllib.parse.urlencode({'data':osm}).encode()),
        ('egib.gml',query('https://mapy.geoportal.gov.pl/wss/service/PZGIK/EGIB/WFS/UslugaZbiorcza',{'service':'WFS','version':'2.0.0','request':'GetFeature','typenames':'ms:budynki','srsName':'urn:ogc:def:crs:EPSG::3857','bbox':','.join(map(str,spec['bounds_3857']))+',urn:ogc:def:crs:EPSG::3857','count':10000}),None),
        ('lod1-wms-capabilities.xml',query('https://mapy.geoportal.gov.pl/wss/service/PZGIK/FOTO/WMS/ModeleBudynkow3D',{'service':'WMS','version':'1.1.1','request':'GetCapabilities'}),None),
    ]
    def job(t):
        try:
            payload=fetch(*t)
            if t[0]=='lod1-wms-capabilities.xml':
                root=ET.fromstring(payload)
                return t[0],len(payload),[(e.findtext('Name'),e.findtext('Title')) for e in root.findall('.//Layer')]
            if t[0]=='egib.gml':
                root=ET.fromstring(payload);return t[0],len(payload),root.attrib,payload[:500].decode('utf-8','replace')
            data=json.loads(payload);return t[0],len(payload),len(data.get('elements',[])),data.get('osm3s'),data.get('remark')
        except Exception as e:return t[0],str(e)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for r in pool.map(job,tasks):print(json.dumps(r,ensure_ascii=False))
