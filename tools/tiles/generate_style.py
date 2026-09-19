import json

def build_style():
    style = {
        "version": 8,
        "name": "Mazovia Offroad v1.1",
        "glyphs": "asset://map/glyphs/{fontstack}/{range}.pbf",
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

    # 1. Background
    style["layers"].append({
        "id": "background",
        "type": "background",
        "paint": {"background-color": "#18181A"}
    })

    # 2. Landcover (Level 5)
    layer("landcover-sand", "fill", "landcover", ["==", "class", "sand"], 
          {"fill-color": "#2A251A", "fill-opacity": 0.8})
    layer("landcover-forest", "fill", "landcover", ["==", "class", "forest"], 
          {"fill-color": "#19241B", "fill-opacity": 0.8})

    # Water
    layer("water", "fill", "water", None, {"fill-color": "#1B4352"})
    layer("waterway", "line", "waterway", None, {"line-color": "#1B4352", "line-width": 2})

    # Buildings
    layer("building", "fill", "building", None, {"fill-color": "#2A2A2E", "fill-opacity": 0.5}, minzoom=13)


    # 3. Transportation (Level 3 & 4)
    # Major Roads (motorway, trunk, primary)
    layer("road-major-casing", "line", "transportation", 
          ["match", ["get", "highway"], ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"], True, False],
          {"line-color": "#0B0B0C", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 2, 15, 6, 18, 14]},
          layout={"line-cap": "round", "line-join": "round"})
          
    layer("road-major", "line", "transportation", 
          ["match", ["get", "highway"], ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"], True, False],
          {"line-color": "#38383B", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1, 15, 4, 18, 10]},
          layout={"line-cap": "round", "line-join": "round"})

    # Secondary Roads (secondary, tertiary)
    layer("road-secondary-casing", "line", "transportation", 
          ["match", ["get", "highway"], ["secondary", "secondary_link", "tertiary", "tertiary_link"], True, False],
          {"line-color": "#0F0F11", "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.5, 15, 5, 18, 12]}, minzoom=9,
          layout={"line-cap": "round", "line-join": "round"})
          
    layer("road-secondary", "line", "transportation", 
          ["match", ["get", "highway"], ["secondary", "secondary_link", "tertiary", "tertiary_link"], True, False],
          {"line-color": "#444449", "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.5, 15, 3, 18, 8]}, minzoom=9,
          layout={"line-cap": "round", "line-join": "round"})

    # Local Roads (unclassified, residential, living_street)
    layer("road-local-casing", "line", "transportation", 
          ["match", ["get", "highway"], ["unclassified", "residential", "living_street", "road"], True, False],
          {"line-color": "#111114", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1, 15, 3, 18, 8]}, minzoom=11,
          layout={"line-cap": "round", "line-join": "round"})
          
    layer("road-local", "line", "transportation", 
          ["match", ["get", "highway"], ["unclassified", "residential", "living_street", "road"], True, False],
          {"line-color": "#303033", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 15, 1.5, 18, 5]}, minzoom=11,
          layout={"line-cap": "round", "line-join": "round"})
          
    # Service / Pedestrian
    layer("road-service", "line", "transportation", 
          ["match", ["get", "highway"], ["service", "footway", "cycleway", "steps", "pedestrian"], True, False],
          {"line-color": "#252528", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.5, 18, 3]}, minzoom=13)


    # 4. Off-Road Rideable Network (Level 2)
    # Surface Color Palette
    surface_color = [
        "match",
        ["get", "surface"],
        ["asphalt", "paved"], "#424244",
        ["concrete", "concrete:plates", "concrete:lanes"], "#535355",
        ["paving_stones", "sett", "cobblestone", "brick", "bricks"], "#4B4B42",
        ["compacted", "fine_gravel"], "#856D4D",
        ["gravel"], "#9C703B",
        ["dirt", "earth", "ground", "unpaved"], "#7D4E2F",
        ["sand"], "#B89E54",
        ["mud"], "#4D3624",
        ["grass"], "#4A6B3A",
        "#6B5D53" # UNKNOWN (Highly visible brownish-grey)
    ]

    # Tracktypes mapped to (dasharray, [width_z10, width_z13, width_z16, width_z18])
    track_types = [
        ("grade1", None, [0.5, 2.0, 5.0, 10.0]),
        ("grade2", None, [0.5, 1.5, 4.0, 8.0]),
        ("grade3", [3, 1.5], [0.5, 1.5, 3.5, 7.0]),
        ("grade4", [2, 2], [0.5, 1.0, 3.0, 6.0]),
        ("grade5", [1.5, 2.5], [0.5, 1.0, 2.5, 5.0]),
        ("unknown", [2, 1], [0.5, 1.5, 3.5, 7.0])
    ]

    # Render Tracks
    for grade, dash, widths in track_types:
        if grade == "unknown":
            flt = ["all", 
                ["==", ["get", "highway"], "track"],
                ["!", ["has", "tracktype"]]
            ]
        else:
            flt = ["all", 
                ["==", ["get", "highway"], "track"],
                ["==", ["get", "tracktype"], grade]
            ]
            
        # Track Casing (Dark outline for visibility)
        layer(f"track-{grade}-casing", "line", "transportation", flt, {
            "line-color": "#0B0B0C",
            "line-width": ["interpolate", ["linear"], ["zoom"], 10, widths[0]+1, 13, widths[1]+1.5, 16, widths[2]+2, 18, widths[3]+3]
        }, layout={"line-cap": "round", "line-join": "round"})
        
        # Track Core
        paint = {
            "line-color": surface_color,
            "line-width": ["interpolate", ["linear"], ["zoom"], 10, widths[0], 13, widths[1], 16, widths[2], 18, widths[3]]
        }
        if dash:
            paint["line-dasharray"] = dash
            
        layer(f"track-{grade}", "line", "transportation", flt, paint, layout={"line-cap": "round", "line-join": "round"})

    # Render Paths/Bridleways
    # Thinner and always dashed to distinguish from tracks
    path_flt = ["match", ["get", "highway"], ["path", "bridleway"], True, False]
    layer("path-casing", "line", "transportation", path_flt, {
        "line-color": "#0B0B0C",
        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1.5, 16, 3.5, 18, 6.0]
    }, layout={"line-cap": "round", "line-join": "round"}, minzoom=11)
    
    layer("path-core", "line", "transportation", path_flt, {
        "line-color": surface_color,
        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 16, 2.0, 18, 4.0],
        "line-dasharray": [2, 2]
    }, layout={"line-cap": "round", "line-join": "round"}, minzoom=11)


    # 5. Access Restrictions & Fords
    # Restrictions (subtle red dashed overlay)
    layer("restriction-overlay", "line", "transportation", 
          ["any", 
            ["==", ["get", "access"], "no"], 
            ["==", ["get", "access"], "private"],
            ["==", ["get", "motor_vehicle"], "no"],
            ["==", ["get", "motorcycle"], "no"]
          ],
          {
              "line-color": "#B33A3A", 
              "line-width": ["interpolate", ["linear"], ["zoom"], 12, 1.5, 15, 3, 18, 6], 
              "line-opacity": 0.6,
              "line-dasharray": [1, 1]
          },
          minzoom=12)

    # Fords (cyan overlay to catch the eye for water crossings)
    layer("ford-overlay", "line", "transportation", 
          ["has", "ford"],
          {
              "line-color": "#00BCD4", 
              "line-width": ["interpolate", ["linear"], ["zoom"], 12, 2, 15, 5, 18, 10], 
              "line-opacity": 0.7
          },
          minzoom=12)

    # 6. Labels (Level 6)
    # Sparse hierarchy
    layer("label-city", "symbol", "place", ["==", ["get", "class"], "city"], 
          paint={"text-color": "#FFFFFF", "text-halo-color": "#111111", "text-halo-width": 2},
          layout={"text-field": ["get", "name"], "text-size": ["interpolate", ["linear"], ["zoom"], 6, 12, 10, 18], "text-font": ["Open Sans Semibold"]},
          minzoom=6, maxzoom=14)
          
    layer("label-town", "symbol", "place", ["==", ["get", "class"], "town"], 
          paint={"text-color": "#E0E0E0", "text-halo-color": "#111111", "text-halo-width": 1.5},
          layout={"text-field": ["get", "name"], "text-size": ["interpolate", ["linear"], ["zoom"], 8, 11, 14, 16], "text-font": ["Open Sans Semibold"]},
          minzoom=8, maxzoom=15)
          
    layer("label-village", "symbol", "place", ["==", ["get", "class"], "village"], 
          paint={"text-color": "#BBBBBB", "text-halo-color": "#111111", "text-halo-width": 1.5},
          layout={"text-field": ["get", "name"], "text-size": ["interpolate", ["linear"], ["zoom"], 10, 10, 15, 14], "text-font": ["Open Sans Semibold"]},
          minzoom=10)

    # Road Names (Highly filtered to avoid clutter, only visible when zoomed in)
    layer("label-road", "symbol", "transportation", 
          ["all", ["has", "name"], ["match", ["get", "highway"], ["track", "path", "residential", "unclassified", "tertiary", "secondary"], True, False]],
          paint={"text-color": "#999999", "text-halo-color": "#18181A", "text-halo-width": 1.5},
          layout={
              "text-field": ["get", "name"], 
              "text-size": ["interpolate", ["linear"], ["zoom"], 14, 10, 18, 13], 
              "text-font": ["Open Sans Semibold"],
              "symbol-placement": "line",
              "text-max-angle": 30,
              "text-letter-spacing": 0.1
          },
          minzoom=14)

    # Output
    with open("app/src/main/assets/mapstyles/mazovia_offroad_v1.json", "w", encoding="utf-8") as f:
        json.dump(style, f, indent=2, ensure_ascii=False)

if __name__ == "__main__":
    build_style()
