# Current Engineering Task

**Task:** Fix false GPS cancellation error and foreground recording notification lifecycle after ZAKOŃCZ.
**Owner:** GEMINI
**Status:** COMPLETED
**Started:** 2026-09-19
**Completed:** 2026-09-19

## Objective
Prevent intentional ride cancellation from throwing a false GPS error in the foreground notification. Ensure `ZAKOŃCZ` gracefully stops the location coroutine, removes the notification, and stops the foreground service safely.

## Requirements
- Propagate `CancellationException` properly; do not swallow real GPS failures.
- Verify `stopForeground` and `stopSelf` are used correctly for the API level.
- No modifications to GraphHopper, MapLibre, styling, or routing.

## Physical Acceptance Test
- **PASSED**: Verified on device that pressing `ZAKOŃCZ` cleanly stops recording and removes the foreground notification without throwing a false "BŁĄD GPS" exception. Subsequent rides start normally.

## Accomplished
- Propagated `kotlinx.coroutines.CancellationException` properly in `TrackRecordingService.kt` to avoid catching intentional job cancellations as GPS errors.
