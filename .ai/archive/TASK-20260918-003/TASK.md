TASK_ID: TASK-20260918-003
TASK_TYPE: IMPLEMENTATION
STATUS: READY
CREATED_BY: GEMINI
CREATED_AT: 2026-09-18T21:59:00+02:00

BASE_COMMIT: fe40343a9fdc82568a2b7521b82842e32192d15a
EXPECTED_BRANCH: main

OBJECTIVE: Add a simple log statement to MainActivity.kt.

CONTEXT: Real Pilot Task 2 of 3. We are verifying your ability to edit exactly one allowed source file and properly structure the final RESULT.md.

CONTEXT_FILES_REQUIRED:
- none

FILES_ALLOWED:
app/src/main/java/pl/mazovia/offroad/MainActivity.kt

FILES_FORBIDDEN:
all files not explicitly listed in FILES_ALLOWED

MAX_FILES_CHANGED: 1
MAX_STEPS_PER_SESSION: 10

REQUIRED_BEHAVIOR:
1. Edit `app/src/main/java/pl/mazovia/offroad/MainActivity.kt`.
2. Add the following line as the first statement inside `onCreate` (immediately after `super.onCreate(savedInstanceState)`):
   `android.util.Log.d("MazoviaOffroad", "MainActivity initialized")`
3. Verify the file compiles by running: `.\gradlew.bat :app:assembleDebug`
4. Stage only `app/src/main/java/pl/mazovia/offroad/MainActivity.kt` and create an implementation commit with the message `local-agent: TASK-20260918-003`.
5. Write your result strictly to `.ai/local_agent/RESULT.md` and USE EXACTLY the schema defined in your instructions (SUMMARY:, FILES_CHANGED:, COMMANDS_RUN:, TESTS_RUN:, TEST_RESULTS:, EVIDENCE:, KNOWN_LIMITATIONS:, UNRESOLVED_ISSUES:, EXACT_BLOCKER:, DECISION_REQUIRED_FROM_GEMINI:, RECOMMENDED_GEMINI_REVIEW:). Do not invent new headers.
6. Replace COMMIT_HASH with the real hash of your commit.

ACCEPTANCE_CRITERIA:
1. `MainActivity.kt` contains the new log statement.
2. Only `MainActivity.kt` is modified and committed.
3. The implementation commit is created with the correct subject.
4. `RESULT.md` follows the exact required schema headers.

TEST_COMMANDS:
1. `.\gradlew.bat :app:assembleDebug`

PRE_EXISTING_CHANGES:
- path: none
  diff_stat: none

DO_NOT_TOUCH:
- Any other application source code
- Any workflow metadata (other than your own checkpoint and result files inside .ai/local_agent)

DELIVERABLE:
- One implementation commit containing the change.
- A canonical RESULT.md strictly written inside .ai/local_agent/.

STOP_CONDITIONS:
- stop if the objective is ambiguous
- stop if a required file is outside FILES_ALLOWED
- stop if the build fails after your change

ROLLBACK_EXPECTATION: If build fails, undo the change and mark as BLOCKED.

MAX_SCOPE: 1 file.
