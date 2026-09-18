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
    print("PMTiles info:")
    print("MinZoom:", header["min_zoom"], "MaxZoom:", header["max_zoom"])

    # Collect distinct surfaces, tracktypes
    surfaces = set()
    tracktypes = set()
    highway_filterable = False
    surface_filterable = False
    tracktype_filterable = False

    samples = []

    # Iterate through all tiles in the archive
    tiles = reader.tiles()
    
    for (z, x, y), data in tiles:
        if header["tile_compression"] == 2: # gzip
            try:
                data = gzip.decompress(data)
            except:
                pass
        
        try:
            mvt = mapbox_vector_tile.decode(data)
        except Exception as e:
            continue
            
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
                
                # Keep a few interesting samples
                h = props.get("highway")
                s = props.get("surface")
                t = props.get("tracktype")
                if h and len(samples) < 10:
                    if (h == "track" and s and t) or (h == "path" and s) or (h == "service" and s) or (s and t):
                        samples.append(props)

    print("---")
    print("Distinct Surfaces:", list(surfaces))
    print("Distinct Tracktypes:", list(tracktypes))
    print("---")
    print("Samples:")
    for s in samples:
        print(s)
    print("---")
    print("highway_filterable:", highway_filterable)
    print("surface_filterable:", surface_filterable)
    print("tracktype_filterable:", tracktype_filterable)
