import json

def build_style():
    style = {
        "version": 8,
        "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
        "sources": {
            "pmtiles_source": {
                "type": "vector",
                "url": "{PMTILES_URI}",
                "maxzoom": 14
            }
        },
        "layers": []
    }

    def layer(id, type, source_layer, filter=None, paint=None, layout=None, minzoom=None, maxzoom=None):
        l = {"id": id, "type": type, "source": "pmtiles_source", "source-layer": source_layer}
        if filter: l["filter"] = filter
        if paint: l["paint"] = paint
        if layout: l["layout"] = layout
        if minzoom is not None: l["minzoom"] = minzoom
        if maxzoom is not None: l["maxzoom"] = maxzoom
        style["layers"].append(l)

    # Background
    style["layers"].append({
        "id": "background",
        "type": "background",
        "paint": {"background-color": "#18181A"}
    })

    # Landcover
    layer("landcover-sand", "fill", "landcover", ["==", "class", "sand"], 
          {"fill-color": "#2C2618"})
    layer("landcover-forest", "fill", "landcover", ["==", "class", "forest"], 
          {"fill-color": "#1B261D"})

    # Water
    layer("water", "fill", "water", None, {"fill-color": "#1F3E4D"})
    layer("waterway", "line", "waterway", None, {"line-color": "#1F3E4D", "line-width": 2})

    # Buildings
    layer("building", "fill", "building", None, {"fill-color": "#222224", "fill-opacity": 0.6})

    # Transportation Base (Paved/Urban Roads - Subdued)
    road_paint = {
        "line-color": "#333336",
        "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1, 15, 4, 18, 12]
    }
    road_casing = {
        "line-color": "#111111",
        "line-width": ["interpolate", ["linear"], ["zoom"], 10, 2, 15, 6, 18, 16]
    }

    # Major roads
    layer("road-major-casing", "line", "transportation", 
          ["match", ["get", "highway"], ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"], True, False],
          road_casing)
    layer("road-major", "line", "transportation", 
          ["match", ["get", "highway"], ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"], True, False],
          {"line-color": "#4A4A4F", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.5, 15, 5, 18, 14]})

    # Minor roads
    layer("road-minor-casing", "line", "transportation", 
          ["match", ["get", "highway"], ["secondary", "tertiary", "unclassified", "residential"], True, False],
          {"line-color": "#18181A", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1, 15, 4, 18, 10]}, minzoom=9)
    layer("road-minor", "line", "transportation", 
          ["match", ["get", "highway"], ["secondary", "tertiary", "unclassified", "residential"], True, False],
          {"line-color": "#3A3A3D", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 15, 3, 18, 8]}, minzoom=9)
          
    # Service / Footway (very subdued)
    layer("road-service", "line", "transportation", 
          ["match", ["get", "highway"], ["service", "footway", "cycleway", "steps"], True, False],
          {"line-color": "#2A2A2C", "line-width": ["interpolate", ["linear"], ["zoom"], 14, 1, 18, 4]}, minzoom=13)

    # ENDURO TRACKS
    # Surface color mapping
    surface_color = [
        "match",
        ["get", "surface"],
        ["sand"], "#E6C975",
        ["gravel"], "#D99B41",
        ["compacted", "fine_gravel"], "#B08D5C",
        ["dirt", "earth", "ground"], "#A3623B",
        ["mud"], "#664B38",
        ["grass"], "#5C8A47",
        ["asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes", "paving_stones", "sett", "cobblestone", "brick"], "#636366",
        "#999999" # Fallback color for unknown surface
    ]

    # Tracktypes
    track_types = [
        ("grade1", None, [0.5, 1.5, 4, 12]), # [zoom10, zoom12, zoom15, zoom18] widths
        ("grade2", None, [0.5, 1.2, 3, 10]),
        ("grade3", [4, 1.5], [0.5, 1, 2.5, 8]),
        ("grade4", [3, 2], [0.5, 1, 2, 6]),
        ("grade5", [2, 3], [0.5, 0.8, 1.5, 5]),
        ("unknown", [1, 1], [0.5, 1, 2, 6])
    ]

    for grade, dash, widths in track_types:
        if grade == "unknown":
            flt = ["all", 
                ["match", ["get", "highway"], ["track", "path", "bridleway"], True, False],
                ["!", ["has", "tracktype"]]
            ]
        else:
            flt = ["all", 
                ["match", ["get", "highway"], ["track"], True, False],
                ["==", ["get", "tracktype"], grade]
            ]
            
        paint = {
            "line-color": surface_color,
            "line-width": ["interpolate", ["linear"], ["zoom"], 10, widths[0], 12, widths[1], 15, widths[2], 18, widths[3]]
        }
        if dash:
            paint["line-dasharray"] = dash
            
        layer(f"track-{grade}-casing", "line", "transportation", flt, {
            "line-color": "#111111",
            "line-width": ["interpolate", ["linear"], ["zoom"], 10, widths[0]+1, 12, widths[1]+1, 15, widths[2]+2, 18, widths[3]+4]
        }, layout={"line-cap": "round", "line-join": "round"})
        
        layer(f"track-{grade}", "line", "transportation", flt, paint, layout={"line-cap": "round", "line-join": "round"})

    # Restrictions Casing
    layer("restriction-casing", "line", "transportation", 
          ["any", 
            ["==", ["get", "access"], "no"], 
            ["==", ["get", "access"], "private"],
            ["==", ["get", "motor_vehicle"], "no"],
            ["==", ["get", "motorcycle"], "no"]
          ],
          {"line-color": "#FF3B30", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 2, 15, 6, 18, 12], "line-opacity": 0.4},
          minzoom=12)

    # Fords
    layer("ford-casing", "line", "transportation", 
          ["has", "ford"],
          {"line-color": "#00E5FF", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 4, 15, 8, 18, 16], "line-opacity": 0.6},
          minzoom=12)

    # Labels
    layer("label-city", "symbol", "place", ["==", ["get", "class"], "city"], 
          paint={"text-color": "#FFFFFF", "text-halo-color": "#000000", "text-halo-width": 2},
          layout={"text-field": ["get", "name"], "text-size": ["interpolate", ["linear"], ["zoom"], 6, 12, 10, 18], "text-font": ["Open Sans Semibold"]},
          minzoom=6, maxzoom=14)
          
    layer("label-town", "symbol", "place", ["==", ["get", "class"], "town"], 
          paint={"text-color": "#DDDDDD", "text-halo-color": "#000000", "text-halo-width": 1.5},
          layout={"text-field": ["get", "name"], "text-size": ["interpolate", ["linear"], ["zoom"], 8, 10, 14, 16], "text-font": ["Open Sans Semibold"]},
          minzoom=8, maxzoom=15)
          
    layer("label-village", "symbol", "place", ["==", ["get", "class"], "village"], 
          paint={"text-color": "#AAAAAA", "text-halo-color": "#000000", "text-halo-width": 1},
          layout={"text-field": ["get", "name"], "text-size": ["interpolate", ["linear"], ["zoom"], 10, 10, 15, 14], "text-font": ["Open Sans Semibold"]},
          minzoom=10)

    # Output
    with open("app/src/main/assets/mapstyles/mazovia_offroad_v1.json", "w", encoding="utf-8") as f:
        json.dump(style, f, indent=2, ensure_ascii=False)

if __name__ == "__main__":
    build_style()
