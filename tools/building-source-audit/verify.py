"""Validate artifacts against spatial, count, compression and provenance invariants."""
import gzip
import hashlib
import json
import math
import zipfile
from collect import OUT, Transformer
from shapely.geometry import shape
from shapely.ops import transform

spec=json.loads((OUT/'area.json').read_text())
m=json.loads((OUT/'metrics.json').read_text())
corridor=shape(spec['corridor_2180'])
tr=Transformer.from_crs(4326,2180,always_xy=True).transform
for name,expected in m['sources'].items():
    raw=(OUT/(name+'.geojson')).read_bytes()
    assert gzip.decompress((OUT/(name+'.geojson.gz')).read_bytes())==raw
    assert len(raw)==expected['geojson_bytes']
    assert (OUT/(name+'.geojson.gz')).stat().st_size==expected['gzip_bytes']
    features=json.loads(raw)['features']
    assert len(features)==expected['buildings']
    assert len({f['properties']['id'] for f in features})==len(features)
    assert sum(f['properties'].get('height_m') is not None for f in features)==expected['with_height']
    for f in features:
        g=transform(tr,shape(f['geometry']))
        assert g.is_valid and not g.is_empty and g.area>0 and g.intersects(corridor)
        h=f['properties'].get('height_m')
        assert h is None or (math.isfinite(h) and h>0)
        assert not {'user','uid'} & f['properties'].keys()
checked=[]
for p in OUT.glob('*.provenance.json'):
    meta=json.loads(p.read_text())
    if 'sha256' not in meta:continue
    raw=OUT/p.name.removesuffix('.provenance.json')
    if not raw.exists() and raw.with_suffix('.txt').exists():raw=raw.with_suffix('.txt')
    assert raw.exists(),raw
    assert hashlib.sha256(raw.read_bytes()).hexdigest()==meta['sha256'],raw
    assert raw.stat().st_size==meta['bytes']
    checked.append(raw.name)
assert spec['segment_count']==2
result={'passed':True,'sources':list(m['sources']),'sha256_verified_files':checked,'checks':['counts, unique IDs, all geometries valid and intersect corridor in EPSG:2180','positive known heights; unknown remains null','gzip roundtrip and byte sizes','no OSM usernames or account IDs in normalized artifacts','original GPX remains two segments','raw source SHA-256 checks'],'not_verified':['field truth','full national coverage','phone FPS, memory or battery','production PMTiles contents']}
(OUT/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
files=[OUT/x for x in ('REPORT.md','AI-HANDOFF.md','metrics.json','area.json','comparison.png','verification.json')]
files+=list(OUT.glob('*.geojson'))+list(OUT.glob('*.geojson.gz'))+list(OUT.glob('*.provenance.json'))
with zipfile.ZipFile(OUT/'building-source-audit-share.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(files):z.write(p,p.name)
with zipfile.ZipFile(OUT/'building-source-audit-share.zip') as z:
    assert z.testzip() is None
    assert 'osm.json' not in z.namelist()
print(json.dumps(result,indent=2))
print('Share ZIP bytes:',(OUT/'building-source-audit-share.zip').stat().st_size)
