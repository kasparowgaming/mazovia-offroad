"""Build an isolated phone workload from the audited LoD1 footprints."""
import array
import hashlib
import json
from pathlib import Path
from collect import OUT, ROOT, Transformer
from shapely.geometry import shape
from shapely.ops import transform, triangulate
from shapely import constrained_delaunay_triangles

DEST=ROOT/'docs/terrain-ahead/building-source-audit/2026-09-27-siedlce/phone-benchmark'
DEST.mkdir(exist_ok=True)
spec=json.loads((OUT/'area.json').read_text())
data=json.loads((OUT/'lod1.geojson').read_text(encoding='utf-8'))
project=Transformer.from_crs(4326,2180,always_xy=True).transform
route=transform(project,shape(spec['route_wgs84']))
x0,y0=route.geoms[0].coords[0]
base=[];buildings=[]
def tri(target,a,b,c,color,normal):
    for p in (a,b,c):target.extend([*p,*normal,*color])
def quad(target,a,b,c,d,color,normal):
    tri(target,a,b,c,color,normal);tri(target,a,c,d,color,normal)
def local(x,y,h=0):return [x-x0,h,y-y0]
minx,miny,maxx,maxy=shape(spec['corridor_2180']).bounds
for x in range(int(minx)-300,int(maxx)+300,25):
    for y in range(int(miny)-300,int(maxy)+300,25):
        quad(base,local(x,y),local(x+25,y),local(x+25,y+25),local(x,y+25),[.34,.43,.32],[0,1,0])
for line in route.geoms:
    for (x,y),(xx,yy) in zip(line.coords,list(line.coords)[1:]):
        dx,dy=xx-x,yy-y;length=(dx*dx+dy*dy)**.5
        for width,height,color in [(6.4,.05,[.55,.53,.47]),(.8,.08,[.1,.8,.87])]:
            ox,oy=dy/length*width/2,-dx/length*width/2
            quad(base,local(x-ox,y-oy,height),local(x+ox,y+oy,height),local(xx+ox,yy+oy,height),local(xx-ox,yy-oy,height),color,[0,1,0])
max_roof_error=0
for feature in data['features']:
    g=transform(project,shape(feature['geometry']));h=feature['properties']['height_m']
    for p in ([g] if g.geom_type=='Polygon' else g.geoms):
        roof=[]
        for t in constrained_delaunay_triangles(p).geoms:roof.append(t)
        error=abs(sum(t.area for t in roof)-p.area)
        max_roof_error=max(max_roof_error,error)
        assert error<.001,(feature['properties']['id'],error)
        for t in roof:
            coords=list(t.exterior.coords)[:3]
            tri(buildings,*[local(x,y,h) for x,y in coords],[.54,.57,.57],[0,1,0])
        for ring in [p.exterior,*p.interiors]:
            for (x,y),(xx,yy) in zip(ring.coords,list(ring.coords)[1:]):
                dx,dy=xx-x,yy-y;length=(dx*dx+dy*dy)**.5
                if length==0:continue
                quad(buildings,local(x,y),local(xx,yy),local(xx,yy,h),local(x,y,h),[.72,.72,.64],[dy/length,0,-dx/length])
for name,vertices in [('base',base),('buildings',buildings)]:
    (DEST/(name+'.bin')).write_bytes(array.array('f',vertices).tobytes())
manifest={'source':'GUGiK LoD1 2024, source year 2023; CC BY 4.0','buildings':len(data['features']),'terrain':'flat synthetic plane; not the actual DEM','route':'actual coordinates of synthetic test_mazovia.gpx fixture, two separate segments','camera_path':[[x-x0,y-y0] for x,y in route.geoms[0].coords],'base_vertices':len(base)//9,'building_vertices':len(buildings)//9,'base_gpu_buffer_bytes':len(base)*4,'building_gpu_buffer_bytes':len(buildings)*4,'max_roof_area_error_m2':max_roof_error,'source_geojson_sha256':hashlib.sha256((OUT/'lod1.geojson').read_bytes()).hexdigest(),'dpr_cap':1.7,'warmup_seconds':2,'sample_seconds':15,'repeats_per_condition':3}
(DEST/'scene.json').write_text(json.dumps(manifest),encoding='utf-8')
print(json.dumps(manifest,indent=2))
