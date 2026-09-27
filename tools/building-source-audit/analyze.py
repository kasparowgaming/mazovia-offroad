"""Reproducible source comparison. No changes to application data or rendering."""
import collections
import gzip
import json
import re
import zipfile
import xml.etree.ElementTree as ET
from collect import OUT, area, Transformer
from shapely import make_valid, STRtree
from shapely.geometry import Polygon, LineString, shape, mapping
from shapely.ops import transform, unary_union, polygonize

SPEC=area()
CORRIDOR=shape(SPEC['corridor_2180'])
TO_METRIC=Transformer.from_crs(4326,2180,always_xy=True).transform
FROM_MERC=Transformer.from_crs(3857,2180,always_xy=True).transform
TO_WGS=Transformer.from_crs(2180,4326,always_xy=True).transform
G32='{http://www.opengis.net/gml/3.2}'
G='{http://www.opengis.net/gml}'
B='{http://www.opengis.net/citygml/building/2.0}'
GEN='{http://www.opengis.net/citygml/generics/2.0}'
QA=collections.Counter()

def clean(g):
    if not g.is_valid:
        QA['repaired_geometries']+=1
        g=make_valid(g)
    if g.geom_type=='GeometryCollection':
        g=unary_union([x for x in g.geoms if x.geom_type in ('Polygon','MultiPolygon')])
    return g

def add(rows,g,props):
    g=clean(g)
    if not g.is_empty and g.area>0 and g.intersects(CORRIDOR):
        rows.append((g,props))

def number(v):
    if v is None:return None
    try:return float(str(v).replace(',','.').removesuffix(' m').strip())
    except ValueError:return None

def osm():
    data=json.loads((OUT/'osm.json').read_text(encoding='utf-8'))
    assert not data.get('remark'),data.get('remark')
    rows=[]; covered=set()
    relations=[e for e in data['elements'] if e['type']=='relation']
    for e in relations:
        outer=[];inner=[]
        for m in e.get('members',[]):
            coords=[(p['lon'],p['lat']) for p in m.get('geometry',[]) if p]
            if len(coords)>1:(inner if m.get('role')=='inner' else outer).append(LineString(coords))
        outerpolys=list(polygonize(unary_union(outer)))
        if not outerpolys:
            QA['osm_unassembled_relations']+=1;continue
        g=unary_union(outerpolys)
        if inner:g=g.difference(unary_union(list(polygonize(unary_union(inner)))))
        add(rows,transform(TO_METRIC,g),osmprops(e))
        covered.update(m['ref'] for m in e.get('members',[]) if m['type']=='way' and m.get('role') in ('outer',''))
    for e in data['elements']:
        if e['type']!='way' or e['id'] in covered:continue
        coords=[(p['lon'],p['lat']) for p in e.get('geometry',[]) if p]
        if len(coords)<4 or coords[0]!=coords[-1]:
            QA['osm_nonclosed_way']+=1;continue
        add(rows,transform(TO_METRIC,Polygon(coords)),osmprops(e))
    return rows

def osmprops(e):
    t=e['tags']
    return dict(id=f"{e['type']}/{e['id']}",height_m=number(t.get('height')),levels=number(t.get('building:levels')),kind=t.get('building'),edited_at=e.get('timestamp'),height_source='OSM height tag' if t.get('height') else None)

def polygon(p,ns,dim=2):
    def ring(el):
        vals=list(map(float,el.find('.//'+ns+'posList').text.split()))
        return [tuple(vals[i:i+dim]) for i in range(0,len(vals),dim)]
    ext=ring(p.find(ns+'exterior'))
    holes=[ring(x) for x in p.findall(ns+'interior')]
    return Polygon([x[:2] for x in ext],[[x[:2] for x in r] for r in holes]),ext

def egib():
    rows=[];seen=set()
    for name in ('egib.gml','egib-city.gml'):
        root=ET.parse(OUT/name).getroot()
        members=root.findall('{http://www.opengis.net/wfs/2.0}member')
        assert len(members)==int(root.attrib['numberReturned'])==int(root.attrib['numberMatched'])
        for member in members:
            e=member[0];fields={c.tag.split('}')[-1]:c.text for c in e}
            id=fields.get('ID_BUDYNKU') or e.attrib.get(G32+'id')
            if id in seen:QA['egib_duplicate_ids']+=1;continue
            seen.add(id)
            polys=[clean(polygon(p,G32)[0]) for p in e.findall('.//'+G32+'Polygon')]
            if not polys:QA['egib_no_polygon']+=1;continue
            add(rows,transform(FROM_MERC,unary_union(polys)),dict(id=id,height_m=None,levels=number(fields.get('KONDYGNACJE_NADZIEMNE')),source_service=name))
    return rows

def lod1():
    rows=[]
    for archive in sorted(OUT.glob('lod1-*-2024.zip')):
        with zipfile.ZipFile(archive) as z:
            for filename in z.namelist():
                if not filename.endswith('.gml'):continue
                with z.open(filename) as f:
                    for event,e in ET.iterparse(f,events=('end',)):
                        if e.tag!=B+'Building':continue
                        surfaces=[polygon(p,G,3) for p in e.findall('.//'+G+'Polygon')]
                        if not surfaces:QA['lod1_no_surfaces']+=1;e.clear();continue
                        minz=min(v[2] for _,coords in surfaces for v in coords)
                        maxz=max(v[2] for _,coords in surfaces for v in coords)
                        bases=[clean(p) for p,coords in surfaces if all(abs(v[2]-minz)<.02 for v in coords) and p.area>0]
                        if not bases:QA['lod1_no_horizontal_base']+=1;e.clear();continue
                        fields={a.attrib['name']:a.findtext(GEN+'value') for a in e.findall(GEN+'stringAttribute')}
                        height=number(e.findtext(B+'measuredHeight'))
                        add(rows,unary_union(bases),dict(id=e.attrib.get(G+'id'),height_m=height,base_elevation_m=minz,solid_height_m=round(maxz-minz,3),source_year=fields.get('aktZrodla'),footprint_version=fields.get('wersjaId'),height_source=fields.get('zrodloDach'),package=archive.name))
                        e.clear()
    return rows

def percentile(v,p):
    if not v:return None
    v=sorted(v);return round(v[round((len(v)-1)*p)],3)

def compare(a,b):
    tree=STRtree([g for g,_ in b]); candidates=[]; overlap_a=set();overlap_b=set()
    for i,(g,_) in enumerate(a):
        for j in tree.query(g,predicate='intersects'):
            j=int(j);h=b[j][0];inter=g.intersection(h).area
            if inter<=0:continue
            overlap_a.add(i);overlap_b.add(j)
            iou=inter/(g.area+h.area-inter)
            if iou>=.5:candidates.append((iou,i,j,g.centroid.distance(h.centroid)))
    useda=set();usedb=set();matches=[]
    for iou,i,j,d in sorted(candidates,reverse=True):
        if i in useda or j in usedb:continue
        useda.add(i);usedb.add(j);matches.append((iou,d))
    return dict(matched_iou_ge_0_5=len(matches),unmatched_a=len(a)-len(matches),unmatched_b=len(b)-len(matches),any_overlap_a=len(overlap_a),any_overlap_b=len(overlap_b),median_iou=percentile([x[0] for x in matches],.5),centroid_distance_m_p50=percentile([x[1] for x in matches],.5),centroid_distance_m_p95=percentile([x[1] for x in matches],.95))

def run():
    sources={'osm':osm(),'egib':egib(),'lod1':lod1()}
    result={'area':{k:SPEC[k] for k in ('source','kind','length_m','half_width_m','area_m2')},'sources':{},'comparisons':{}}
    for name,rows in sources.items():
        features=[dict(type='Feature',geometry=mapping(transform(TO_WGS,g)),properties=p) for g,p in rows]
        fc=dict(type='FeatureCollection',attribution={'osm':'© OpenStreetMap contributors, ODbL','egib':'Public EGiB WFS: city Siedlce and national aggregation; see report','lod1':'GUGiK, LoD1 2024, CC BY 4.0'}[name],features=features)
        raw=json.dumps(fc,ensure_ascii=False,separators=(',',':')).encode()
        (OUT/(name+'.geojson')).write_bytes(raw)
        compressed=gzip.compress(raw,mtime=0)
        (OUT/(name+'.geojson.gz')).write_bytes(compressed)
        result['sources'][name]=dict(buildings=len(rows),with_height=sum(p.get('height_m') is not None for _,p in rows),with_levels=sum(p.get('levels') is not None for _,p in rows),footprint_area_m2=round(sum(g.area for g,_ in rows),1),geojson_bytes=len(raw),gzip_bytes=len(compressed),source_years=dict(collections.Counter(p.get('source_year') for _,p in rows if p.get('source_year'))),footprint_versions=dict(collections.Counter(p.get('footprint_version') for _,p in rows if p.get('footprint_version'))),source_services=dict(collections.Counter(p.get('source_service') for _,p in rows if p.get('source_service'))),edit_dates_range=[min([p['edited_at'] for _,p in rows if p.get('edited_at')],default=None),max([p['edited_at'] for _,p in rows if p.get('edited_at')],default=None)])
        if name=='lod1':result['sources'][name]['height_solid_delta_max_m']=max((abs(p['height_m']-p['solid_height_m']) for _,p in rows if p['height_m'] is not None),default=None)
    for a,b in [('osm','egib'),('lod1','egib'),('osm','lod1')]:result['comparisons'][a+'_vs_'+b]=compare(sources[a],sources[b])
    result['qa']=dict(QA)
    (OUT/'metrics.json').write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return sources,result

if __name__=='__main__':run()
