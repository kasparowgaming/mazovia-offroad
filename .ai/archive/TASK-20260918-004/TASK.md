TASK_ID: TASK-20260918-004
TASK_TYPE: IMPLEMENTATION
STATUS: READY
CREATED_BY: GEMINI
CREATED_AT: 2026-09-18T22:08:00+02:00

BASE_COMMIT: 62a55f9e00af00950e9da8ec5184a0d14842e8a3
EXPECTED_BRANCH: main

OBJECTIVE: Add a trivial string resource to strings.xml.

CONTEXT: Real Pilot Task 3 of 3. We are verifying your ability to edit XML files safely.

CONTEXT_FILES_REQUIRED:
- none

FILES_ALLOWED:
app/src/main/res/values/strings.xml

FILES_FORBIDDEN:
all files not explicitly listed in FILES_ALLOWED

MAX_FILES_CHANGED: 1
MAX_STEPS_PER_SESSION: 10

REQUIRED_BEHAVIOR:
1. Edit `app/src/main/res/values/strings.xml`.
2. Add `<string name="pilot_test">Pilot 3</string>` inside the `<resources>` block.
3. Verify the file compiles by running: `.\gradlew.bat :app:assembleDebug`
4. Stage only `app/src/main/res/values/strings.xml` and create an implementation commit with the message `local-agent: TASK-20260918-004`.
5. Write your result strictly to `.ai/local_agent/RESULT.md` using the exact canonical schema.
6. Replace COMMIT_HASH with the real hash of your commit.

ACCEPTANCE_CRITERIA:
1. `strings.xml` contains the new string resource.
2. Only `strings.xml` is modified and committed.
3. The implementation commit is created with the correct subject.

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
