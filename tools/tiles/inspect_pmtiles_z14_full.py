import sys
import gzip
import json
from pmtiles.reader import Reader, MmapSource
import mapbox_vector_tile

pmtiles_path = sys.argv[1]

with open(pmtiles_path, 'r+b') as f:
    source = MmapSource(f)
    reader = Reader(source)
    header = reader.header()
    
    # scan ALL tiles
    def get_tiles():
        # brute force search for keys or use something else?
        pass
    
    # We can just check tile 14/9343/5361 directly since we know where it is, or we can check the root directory.
    
    # Bbox: 22.20, 52.12, 22.35, 52.22
    import math
    def deg2num(lat_deg, lon_deg, zoom):
        lat_rad = math.radians(lat_deg)
        n = 2.0 ** zoom
        xtile = int((lon_deg + 180.0) / 360.0 * n)
        ytile = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
        return (xtile, ytile)
        
    x1, y1 = deg2num(52.22, 22.20, 14)
    x2, y2 = deg2num(52.12, 22.35, 14)
    print(f"Checking x:{x1}-{x2}, y:{y1}-{y2}")
    
    found = 0
    for x in range(min(x1,x2), max(x1,x2)+1):
        for y in range(min(y1,y2), max(y1,y2)+1):
            data = reader.get(14, x, y)
            if data:
                found += 1
                try: data = gzip.decompress(data)
                except: pass
                
                try: mvt = mapbox_vector_tile.decode(data)
                except: continue
                
                print(f"Tile 14/{x}/{y} layers:", list(mvt.keys()))
                if "transportation" in mvt:
                    for feature in mvt["transportation"]["features"]:
                        props = feature["properties"]
                        if "surface" in props or "highway" in props:
                            print(props)

    print("Found tiles:", found)
