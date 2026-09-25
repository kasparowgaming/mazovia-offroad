"""Terrain Ahead offline DEM pipeline (TA-001B).

GUGiK NMT 1 m (PL-EVRF2007-NH) -> EPSG:3857 XYZ raster -> Terrain-RGB (mapbox) PNG -> PMTiles v3.
See tools/terrain/README.md and docs/terrain-ahead/DESIGN.md §7-§9.
"""

PIPELINE_VERSION = "ta001b-pipeline-1"
