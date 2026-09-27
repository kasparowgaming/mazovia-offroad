"""Static spatial comparison using actual footprints, with no invented basemap."""
import json
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from collect import OUT, Transformer
from shapely.geometry import shape
from shapely.ops import transform

spec=json.loads((OUT/'area.json').read_text())
m=json.loads((OUT/'metrics.json').read_text())
tr=Transformer.from_crs(4326,2180,always_xy=True).transform
corridor=shape(spec['corridor_2180']);route=transform(tr,shape(spec['route_wgs84']))
img=Image.new('RGB',(1920,1320),'#0c171d');d=ImageDraw.Draw(img)
def font(size,bold=False):return ImageFont.truetype('C:/Windows/Fonts/'+('segoeuib.ttf' if bold else 'segoeui.ttf'),size)
def text(x,y,t,size=22,color='#dfe9ed',bold=False):d.text((x,y),t,font=font(size,bold),fill=color)
text(45,28,'Budynki wokół trasy: trzy źródła, ten sam obszar',38,bold=True)
text(45,88,'Siedlce • testowy GPX 1,86 km • po 200 m z każdej strony • odczyt 27.09.2026',24,'#a9c0cc')
minx,miny,maxx,maxy=corridor.bounds
scale=min(540/(maxx-minx),825/(maxy-miny))
for idx,(name,title,col) in enumerate([('osm','OpenStreetMap','#54c9f3'),('egib','EGiB','#6ed3a5'),('lod1','GUGiK LoD1','#efbb69')]):
    left=40+idx*630
    d.rounded_rectangle((left,150,left+605,1182),radius=18,fill='#16262f')
    s=m['sources'][name]
    text(left+22,168,title,29,col,True)
    text(left+22,212,f"{s['buildings']} obiektów • {s['with_height']} z wysokością",22)
    text(left+22,246,f"{s['with_levels']} z liczbą kondygnacji",20,'#a9c0cc')
    ox=left+302-(maxx-minx)*scale/2;oy=310+(maxy-miny)*scale
    def pt(x,y):return (ox+(x-minx)*scale,oy-(y-miny)*scale)
    def polys(g,fill,outline=None):
        for p in ([g] if g.geom_type=='Polygon' else g.geoms):
            d.polygon([pt(x,y) for x,y in p.exterior.coords],fill=fill)
            if outline:d.line([pt(x,y) for x,y in p.exterior.coords],fill=outline,width=2)
            for r in p.interiors:d.polygon([pt(x,y) for x,y in r.coords],fill='#16262f')
    polys(corridor,'#233a43','#3c535b')
    for f in json.loads((OUT/(name+'.geojson')).read_text(encoding='utf-8'))['features']:
        polys(transform(tr,shape(f['geometry'])),col)
    for seg in route.geoms:d.line([pt(x,y) for x,y in seg.coords],fill='#ffffff',width=3)
    text(left+540,295,'N ↑',20)
    by=1137;d.line((left+24,by,left+24+200*scale,by),fill='#ffffff',width=3)
    text(left+24,by+6,'200 m',18)
text(45,1200,'Biała linia: syntetyczny GPX, nie potwierdzona droga. Budynki zachowują pełne obrysy.',23)
text(45,1240,'Więcej obiektów nie oznacza większej dokładności. Wysokość LoD1: dane źródłowe 2023.',23,'#a9c0cc')
text(45,1282,'© OpenStreetMap contributors (ODbL) • EGiB: publiczne WFS • GUGiK LoD1 (CC BY 4.0)',17,'#8097a3')
img.save(OUT/'comparison.png')
print(OUT/'comparison.png')
