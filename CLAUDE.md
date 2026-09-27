# Mazovia Offroad

## Project
- Android multi-module Kotlin app. Modules: `app`, `domain`, `data`, `routing`, `navigation`, `designsystem`, `terrain`
  (all Android modules: unit tests are `:<module>:testDebugUnitTest`).
- Python tooling: `tools/terrain` (terrain DEM pipeline, validation evidence) and `tools/tiles` (map tiles).
- Design docs: `docs/terrain-ahead/` (`AUDIT.md`, `DESIGN.md`).

## Windows
- Primary environment is Windows / PowerShell. Use `.\gradlew.bat`, never `./gradlew`.
- Terrain tooling requires `PYTHONHASHSEED=0`; project settings provide it.
- Do not set `MSYS_NO_PATHCONV=1` globally. If a particular Git Bash invocation needs path conversion disabled, scope
  it to that command: `MSYS_NO_PATHCONV=1 node script.js C:/path`. In PowerShell, use Windows paths and `$env:NAME`
  for environment variables (for example, `$env:PYTHONHASHSEED = '0'`).
- The terrain pipeline venv lives outside the repo (`$env:LOCALAPPDATA\MazoviaOffroad\terrain-build\tools\venv` in
  PowerShell); use its `python.exe` when `python` is not on PATH.
- Node.js is required by the JavaScript hooks in `.claude/hooks/` and by the user-level `/ta-baseline` skill.

## Git safety
- Never commit or push unless the user explicitly asks in that turn. Ordinary local `git commit` and non-force
  `git push` prompt for approval; remote Claude Code sessions may allow those ordinary forms.
- Force push, destructive reset, forced clean, stash mutation, restore, checkout path restoration, forced branch
  deletion, and rebase are blocked in every environment. Merge, cherry-pick, revert, switch, and checkout that
  changes HEAD prompt for approval.
- Never discard existing work. Hook approval does not replace task authorization.

## Task workflow
Baseline verification → implementation → tests → independent source review → required fixes → focused final review →
explicit commit approval → commit → push (only when requested). Reviews run in a fresh session that did not implement
the change.

## Task scope
- `TA-xxx` / `TASK-xxx` prompts define the allowed write scope. Stay inside it; no incidental cleanup outside it.
- Always end implementation with a scope check (`git status --porcelain=v1 --untracked-files=all`). The user-level
  `/ta-baseline` skill automates pre/post checks and is manual-only.

## Terrain Ahead
- `AUDIT.md` and `DESIGN.md` are the architectural sources; `DESIGN.md` is authoritative for Terrain Ahead semantics.
  Verify their blob hashes when a task states expected values.
- Terrain presentation code must not change navigation semantics. Terrain failure must never break MAP/navigation.
- Unavailable elevation is never represented as 0 m.
- Raw / Filtered / Grade / Display are distinct. Display/render blending never feeds grade.
- Large DEM / generated terrain artifacts (NMT sheets, `.pmtiles`, venvs, SDKs) stay outside Git unless explicitly approved.
- Terrain pipeline source bytes are part of build provenance (`pipeline_sha256`); `tools/terrain/.gitattributes` keeps them LF.

## Frozen architecture
- The frozen-by-default set is `DESIGN.md` §24 "UNCHANGED — FROZEN", mirrored in `.claude/frozen-paths.txt`; that list
  also self-protects the project guard configuration, hooks, and scripts.
- Direct Write/Edit/MultiEdit/NotebookEdit targeting a frozen path is denied. Shell-write detection is best-effort and
  asks before writes to frozen destinations; the mandatory post-run scope check is the backstop.
- Reads and copies from frozen files are allowed. Changing a frozen file requires raising **OPEN DECISION — FROZEN
  CLASS EXCEPTION** and explicit user approval.
- Frozen files are permanent architecture; task scope is separate and set per task.

## Testing
- Prefer narrow tests first.
- Terrain unit tests: `.\gradlew.bat :terrain:testDebugUnitTest` (add `--rerun` when Gradle reports UP-TO-DATE and a
  fresh run is required).
- Terrain Python: `python -m pytest -p no:cacheprovider tools/terrain/tests`.
- Guard validation: `node .claude/scripts/guard-tests.js`.
- Never claim physical-device validation from emulator results.

## Evidence labels
Use in reports: **VERIFIED** (checked in this run), **SOURCE-READ** (read from code/docs, not executed), **DERIVED**
(computed from verified values), **NOT REPRODUCED**, **INCONCLUSIVE**.

## Stan prac
- TA-007B (korytarz D) na `main`: **ae90629** (2026-09-27), S25 VERIFIED (T7, 9 scen §22.5, jazda fix3 z reroute).
- Kierunek (D → TA-007C → G-3D): https://claude.ai/artifact/Vq3L8uJJ8vPg3wXyPPCx1d; wygląd: `docs/terrain-ahead/visualizations/driver-view/` (nieśledzone).
- TA-000B-C3 (DESIGN §0.3, 6d9fcb5): V1 = korytarz D + cieniowana siatka; luka = szary „brak danych”; po reroute
  zamrożony wyszarzony widok, „ładowanie” po 1 s.
- TA-007C (5b77d08): `TerrainGrid` (25 m / ±200 m, dziury NaN), `buildGrid` (Mutex), siatka w `CorridorPainter`, `Reloading`.
- TA-000B-C4 (DESIGN §0.4, zaakceptowane 2026-09-27): bryły budynków OSM przy trasie, osobne archiwum
  `buildings_mazowsze_z14.pmtiles`; OD-10 = kondygnacje × 3 m / blok 3 m; OD-11 = LoD1 później. Następne: TA-007D (§23), pilot T7.
  S25 VERIFIED mock jazda T7 z prędkością: 83 przebudowy, build p95 13 ms (z prefetchem), draw p95 3,6 ms, gfxinfo jank 0,12 %.
- Review TA-007C (2026-09-27) + poprawki R1–R5: szybszy test pasa, `placeIn`, reset siatki po błędzie, sceny C3 10–15 i bench
  z siatką (S25 VERIFIED 4/4: grid sample warm p95 4,2 ms, draw z siatką p95 ≤ 9 ms). Testy: terrain 153, app 107.
- Końcowe review poprawek (2026-09-27, świeża sesja): bez blokerów; C3 i TA-007C skomitowane osobno lokalnie, push na polecenie.
  Drobne: mgła siatki liczona od kamery (trasa od jeźdźca); brak testu opóźnienia „ładowanie” 1 s.
- Otwarte: build siatki na realnym archiwum po R1 niezmierzony (§21 cel ≤ 10 ms); guard-tests.js pada od C2 (przypięty blob
  DESIGN C1 b1de916, zmiana pliku zamrożonego = decyzja); TA-007B F2–F5; reroute / zakręt za grzbietem / dojazd / landscape
  niesprawdzone na S25; znak nawierzchni pod znakiem manewru; TASK-NAV-REROUTE-001 (6aa89fc) bez review.
- Mock bez prędkości = widok stoi (§15.4); jazda z prędkością: feeder dex przez `app_process` (memory).
- Na S25 nie uruchamiać `connectedAndroidTest` (odinstalowuje aplikację i dane z Android/data); użyć `adb install -r` + `am instrument`.
