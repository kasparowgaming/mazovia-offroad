"""Terrain Ahead DEM pipeline - single documented entry point (TA-001B, DESIGN §7.7).

    python tools/terrain/build_terrain.py all --work-dir <dir> --output-dir <dir>

Stages (each can be run alone): fetch | build | reference | probe | fixtures | all
Large data (source sheets, archives, CSVs) is written only to --work-dir / --output-dir, never into the repository.
Default work dir: %LOCALAPPDATA%\\MazoviaOffroad\\terrain-build (Windows) or ~/.cache/mazovia-offroad/terrain-build.
Run with PYTHONHASHSEED=0: the reference PMTiles writer deduplicates tiles with Python's salted hash().
"""

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from terrain_pipeline import validation_set  # noqa: E402

DECL = os.path.join(HERE, "validation", "validation_routes.json")


def default_work_dir() -> str:
    base = os.environ.get("LOCALAPPDATA")
    if base:
        return os.path.join(base, "MazoviaOffroad", "terrain-build")
    return os.path.join(os.path.expanduser("~"), ".cache", "mazovia-offroad", "terrain-build")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["fetch", "build", "reference", "probe", "fixtures", "geoid", "all"])
    ap.add_argument("--work-dir", default=default_work_dir())
    ap.add_argument("--output-dir", default=None, help="archives + manifests (default <work-dir>/output)")
    ap.add_argument("--zooms", default="15,14", help="first = primary runtime zoom")
    ap.add_argument("--build-label", default="validation", help="output sub-directory name")
    a = ap.parse_args()
    if os.environ.get("PYTHONHASHSEED") != "0":
        sys.exit("set PYTHONHASHSEED=0 (reference PMTiles writer dedup uses hash(); see README)")
    repo = os.path.dirname(os.path.dirname(HERE))
    work = os.path.abspath(a.work_dir)
    if os.path.commonpath([work, repo]) == repo:
        sys.exit("--work-dir must be outside the repository")
    out = os.path.abspath(a.output_dir or os.path.join(work, "output"))
    if os.path.commonpath([out, repo]) == repo:
        sys.exit("--output-dir must be outside the repository")
    os.makedirs(work, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    zooms = [int(z) for z in a.zooms.split(",")]
    decl = validation_set.load(DECL)
    validation_set.verify_frozen(decl)
    argv = " ".join(["build_terrain.py"] + [x for x in sys.argv[1:]])

    if a.stage in ("fetch", "all"):
        from terrain_pipeline import fetch
        fetch.fetch(decl, work, zooms[0])
    if a.stage in ("build", "all"):
        import json
        from terrain_pipeline import build
        manifests = [build.build_archive(decl, work, os.path.join(out, a.build_label), z, repo, argv, primary=(z == zooms[0]))
                     for z in zooms]
        if a.build_label == "validation":
            with open(os.path.join(HERE, "validation", "build_manifest.json"), "w", encoding="utf-8", newline="\n") as f:
                json.dump({"schema_version": "ta001b-build-manifest-set-1", "primary_zoom": zooms[0], "builds": manifests},
                          f, ensure_ascii=False, indent=1)
                f.write("\n")
    if a.stage in ("reference", "all"):
        from terrain_pipeline import reference
        reference.run(decl, work)
    if a.stage in ("probe", "all"):
        from terrain_pipeline import probe
        probe.run(work, os.path.join(out, a.build_label), zooms)
    if a.stage in ("fixtures", "all"):
        from terrain_pipeline import fixtures
        fixtures.run(os.path.join(work, "fixtures"))
        fixtures.u5_synthetic(os.path.join(work, "fixtures"))
        for z in zooms:
            arc = os.path.join(out, a.build_label, f"terrain_z{z}.pmtiles")
            if os.path.exists(arc):
                fixtures.expect_archive(arc, os.path.join(work, "fixtures", f"prod_terrain_z{z}.expected.json"))
    if a.stage in ("geoid", "all"):
        from terrain_pipeline import geoid
        geoid.run(work, decl)


if __name__ == "__main__":
    main()
