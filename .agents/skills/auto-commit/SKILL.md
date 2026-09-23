---
name: auto-commit
description: >-
  Automates the complete RoadSense pre-commit validation, testing, hygiene verification,
  artifact mirroring, and conventional git commit pipeline. Activate whenever the user invokes
  /auto-commit, /commit, or asks to commit changes.
---

# RoadSense Automated Git Commit Skill

This skill defines the autonomous, non-destructive git commit procedure for the RoadSense repository. Whenever invoked via `/auto-commit`, `/commit`, or natural language ("commit changes"), the agent must execute the following 6-step pipeline in order.

---

## Pre-Requisites & Guardrails

1. **JBR Java Home (Mandatory):** Always set `$env:JAVA_HOME` to Android Studio's bundled JBR before executing Gradle commands:
   ```powershell
   $env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"
   ```
2. **Never Run Destructive Commands:** Strictly forbid `git reset --hard` or unprompted working tree wipes.
3. **No Large Media or Binary Staging:** Ensure `.bin`, `.mp4`, `logs/`, `__pycache__/`, or `*.pyc` files are NEVER committed to git.
4. **Targeted Staging:** Use targeted `git add <file1> <file2>` rather than blind `git add .`.

---

## Execution Workflow

### Step 1: Run JVM Unit Tests
Run the test suite to ensure no regressions exist before committing:
```powershell
$env:JAVA_HOME = "C:\Users\rakadu1.AHEAD\Android_Studio\android-studio-quail4-windows\android-studio\jbr"; ./gradlew testDebugUnitTest
```
* **Success:** If tests pass (`BUILD SUCCESSFUL`), proceed to Step 2.
* **Failure:** If any test fails, **HALT IMMEDIATELY**. Report the failure details to the user and do not proceed with the commit.

---

### Step 2: Check Working Tree & Hygiene Guardrails
Run `git status` to inspect all modified and untracked files:
```powershell
git status
```
* Verify that all untracked files are intentional project files (code, tests, config, or documentation).
* Confirm that no raw sensor recordings (`*.bin`, `*.mp4`, `*.MF4`), flight logs (`logs/`), or Python bytecode files (`__pycache__/`, `*.pyc`) are untracked or staged.
* If any temporary files or cache directories appear, ensure `.gitignore` is updated to exclude them.

---

### Step 3: Rule 6 Implementation & Walkthrough Mirroring Check
Check if any implementation plans, architectural specifications, or walkthroughs were created or modified in the agent's internal brain artifact directory during the current session:
* If a new plan or walkthrough was written to the brain directory, verify that an identical copy is mirrored to:
  * `intel/Implementations/<plan_name>.md` (for implementation plans / walkthroughs)
  * `intel/<report_name>.md` (for general architectural feasibility reports)
* If unmirrored artifacts exist, mirror them immediately before staging.

---

### Step 4: Targeted Staging
Stage all intentional modified and new files using targeted `git add` commands:
```powershell
git add <file1> <file2> <file3> ...
```
Verify the staged files:
```powershell
git status
```
Ensure that `Changes to be committed:` contains only the intended files.

---

### Step 5: Formulate Conventional Semantic Commit Message
Analyze the staged changes (`git diff --staged --stat`) and compose a high-quality conventional commit message:
* **Prefixes:**
  * `feat(...)`: New features, tools, or sensors
  * `fix(...)`: Bug fixes, math corrections, crash resolutions
  * `refactor(...)`: Code restructuring without functional changes
  * `docs(...)`: Documentation, guides, or specifications
  * `test(...)`: Unit test additions or fixtures
  * `chore(...)`: Build scripts, `.gitignore`, or configuration updates
* **Format:**
  ```text
  <type>(<scope>): <concise imperative title under 72 chars>

  - Detailed bullet point 1 explaining the 'why' and 'what'
  - Detailed bullet point 2 detailing affected modules or algorithms
  - Detailed bullet point 3 listing new tools or documentation
  ```

---

### Step 6: Execute Commit & Report
Execute the commit:
```powershell
git commit -m "<commit_message>"
```
Verify the commit was recorded cleanly and inspect the commit statistics:
```powershell
git log -1 --stat
git status
```

Report the following back to the user:
1. Commit hash and title
2. Number of files changed, insertions, and deletions
3. Summary of key changes included in the commit
4. Current branch status (e.g. `Your branch is ahead of 'origin/...' by N commits`)
