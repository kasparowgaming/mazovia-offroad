DECISION-001 — Adopt Qwen/Gemini dual-agent workflow
Date: 2026-09-18
Decision: Adopt the file-based multi-agent workflow defined in Mazovia_Gemini_Qwen_Workflow_v3.5.1_FINAL_PC_VERIFIED_CONTEXT_SAFE.txt.
Reason: To delegate bounded tasks to local Qwen.
Affected areas: .ai/ workflow metadata, scripts/
Do not revisit unless: Required by workflow changes.

DECISION-002 — Preserve OSMDroid fallback and investigate MapLibre Native
Date: 2026-09-18
Decision: Evaluate MapLibre Native as the preferred future map-rendering candidate, but preserve current working OSMDroid implementation as fallback. Do not replace OSMDroid until a MapLibre POC is physically verified. Do NOT implement Mapsforge yet. GraphHopper routing must remain independent from map-rendering-engine experiments. Do not fabricate terrain/surface data when real source data is unavailable.
Reason: MapLibre Native offers GPU vector rendering, smoother native camera primitives, bearing/zoom/tilt animation, custom Style JSON, offline vector maps, and PMTiles feasibility, enabling greater control over off-road cartography.
Affected areas: Map rendering, map UI components.
Do not revisit unless: MapLibre Native POC proves unfeasible on target hardware.

DECISION-003 - Offline MapLibre Vector Map Architecture
Date: 2026-09-19
Decision: Use MapLibre Native with offline PMTiles vector archives (asset:// or file://) and bundled offline glyph PBFs (asset://). No runtime network dependency is permitted for map rendering.
Reason: Enduro riders frequently operate outside cellular coverage. Using local PMTiles and bundled local font glyphs guarantees total reliability without relying on map tiles or font PBFs from an online server.
Affected areas: MapLibre style JSON, asset bundle, PMTiles architecture.
Do not revisit unless: Vector tiles are abandoned entirely.
