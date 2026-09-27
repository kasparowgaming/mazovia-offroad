"""Read-only public-service discovery for a building source comparison."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/terrain-ahead/building-source-audit/2026-09-27-siedlce'
OUT.mkdir(parents=True, exist_ok=True)
URLS = {
    'egib-capabilities': 'https://mapy.geoportal.gov.pl/wss/service/PZGIK/EGIB/WFS/UslugaZbiorcza?SERVICE=WFS&REQUEST=GetCapabilities&VERSION=2.0.0',
    'services-page': 'https://www.geoportal.gov.pl/pl/usluga/wykaz-uslug/',
    'wms-page': 'https://www.geoportal.gov.pl/pl/usluga/uslugi-przegladania-wms-i-wmts/',
    'lod1-info': 'https://www.geoportal.gov.pl/pl/dane/inne-dane/modele-3d-budynkow/',
}
def fetch(item):
    name,url=item
    try:
        req=urllib.request.Request(url,headers={'User-Agent':'MazoviaOffroad-BuildingSourceAudit/1.0'})
        with urllib.request.urlopen(req,timeout=45) as r:
            data=r.read(); status=r.status
        (OUT/(name+'.txt')).write_bytes(data)
        record={'url':url,'status':status,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
        (OUT/(name+'.provenance.json')).write_text(json.dumps(record,indent=2),encoding='utf-8')
        text=data.decode('utf-8','replace')
        if name=='egib-capabilities':
            root=ET.fromstring(data)
            ns={'wfs':'http://www.opengis.net/wfs/2.0'}
            record['feature_types']=[ET.tostring(x,encoding='unicode')[:1600] for x in root.findall('.//wfs:FeatureType',ns)]
        else:
            record['links']=[x for x in re.findall(r'''(?:src|href)=["']([^"']+)''',text) if any(t in x.lower() for t in ['.js','lod','budyn','json'])]
            record['matching_lines']=[line[:1000] for line in text.splitlines() if any(t in line.lower() for t in ['pzgik/bud','modele3d','lod1','uslugi.json'])][:20]
        return name,record
    except Exception as e: return name,{'error':str(e)}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    for name,result in pool.map(fetch,URLS.items()): print(name,json.dumps(result,ensure_ascii=False))
