import sys
import gzip
from pmtiles.reader import Reader, MmapSource
import mapbox_vector_tile

pmtiles_path = sys.argv[1]

highway_filterable = False
surface_filterable = False
tracktype_filterable = False
surfaces = set()
tracktypes = set()
samples = []

with open(pmtiles_path, 'r+b') as f:
    source = MmapSource(f)
    reader = Reader(source)
    header = reader.header()
    
    import math
    def deg2num(lat_deg, lon_deg, zoom):
        lat_rad = math.radians(lat_deg)
        n = 2.0 ** zoom
        xtile = int((lon_deg + 180.0) / 360.0 * n)
        ytile = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
        return (xtile, ytile)
        
    x1, y1 = deg2num(52.22, 22.20, 14)
    x2, y2 = deg2num(52.12, 22.35, 14)
    
    for x in range(min(x1,x2), max(x1,x2)+1):
        for y in range(min(y1,y2), max(y1,y2)+1):
            data = reader.get(14, x, y)
            if not data: continue
            
            try: data = gzip.decompress(data)
            except: pass
            
            try: mvt = mapbox_vector_tile.decode(data)
            except: continue
                
            if "transportation" in mvt:
                for feature in mvt["transportation"]["features"]:
                    props = feature["properties"]
                    if "highway" in props: highway_filterable = True
                    if "surface" in props: 
                        surface_filterable = True
                        surfaces.add(props["surface"])
                    if "tracktype" in props:
                        tracktype_filterable = True
                        tracktypes.add(props["tracktype"])
                    
                    h = props.get("highway")
                    s = props.get("surface")
                    t = props.get("tracktype")
                    if h and len(samples) < 15:
                        if (h == "track" and s and t) or (h == "path" and s) or (s == "sand" or s == "gravel"):
                            samples.append(props)

print("highway_filterable:", highway_filterable)
print("surface_filterable:", surface_filterable)
print("tracktype_filterable:", tracktype_filterable)
print("Surfaces:", list(surfaces))
print("Tracktypes:", list(tracktypes))
print("Samples:")
for s in samples:
    print(s)
