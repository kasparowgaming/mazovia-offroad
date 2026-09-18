import sys
import gzip
import json
from pmtiles.reader import Reader, MmapSource
import mapbox_vector_tile

pmtiles_path = sys.argv[1]
z = int(sys.argv[2])
x = int(sys.argv[3])
y = int(sys.argv[4])

with open(pmtiles_path, 'r+b') as f:
    source = MmapSource(f)
    reader = Reader(source)
    data = reader.get(z, x, y)
    
    if data:
        try:
            data = gzip.decompress(data)
        except:
            pass
        
        mvt = mapbox_vector_tile.decode(data)
        if "transportation" in mvt:
            for feature in mvt["transportation"]["features"]:
                props = feature["properties"]
                h = props.get("highway")
                s = props.get("surface")
                t = props.get("tracktype")
                if h:
                    print(f"highway={h} surface={s} tracktype={t}")
    else:
        print("Tile not found")
