# Running many agents on BestNote

Each agent takes **one task ID**, works in **its own git worktree**, touches **only its workstream's modules**, verifies, and opens a PR. The lead (a human or the coordinating agent) merges and starts the next wave.

## Lead: starting an agent
1. Pick a task from the current wave below whose dependencies are merged.
2. Spawn the agent with the prompt in [`agent-prompt.md`](agent-prompt.md), filling in `{TASK}`.
3. Concurrency on the 8 GB dev Mac: **2 agents + 1 emulator**. More agents than that thrash memory. A bigger machine scales linearly; each agent needs ~2 GB.
4. Merge order: smallest PR first. After each merge, the remaining agents rebase (`git pull --rebase origin main`).

## Agent: the loop
```bash
dir=$(scripts/agent/new-task.sh INK-03 highlighter)   # isolated worktree + branch
cd "$dir"
# … implement …
scripts/agent/verify.sh :engine:ink                    # only your modules: fast, low RAM
scripts/agent/verify.sh :engine:ink --app              # + APK when UI/integration changed
scripts/agent/device.sh install && scripts/agent/device.sh shot ink-03   # shared emulator, locked
git push -u origin HEAD && gh pr create --fill          # PR title: "[INK-03] Highlighter"
```

## Design gate
UI tasks (DS, LIB, PAGES, editor chrome, AI-05/07) start only after the [design board](../../design/) is approved. They implement it exactly. Non-UI tasks (storage, stylus pipeline, layout engine) can run before approval.

## Waves (tasks in a wave touch different modules → safe in parallel)
| Wave | Tasks (module) | Notes |
|---|---|---|
| **1** | DOC-01 `.bnote` codec (`core:storage`) · INK-01 + INK-03 pens/highlighter (`engine:ink`) · SPEN-01 + SPEN-03 + SPEN-04 event pipeline/hover/side button (`engine:stylus`, canvas hook) · DS-01 + DS-02 theme/motion (`core:designsystem`) · AI-03 Gemini provider (new `ai:vision-gemini`) · QA-02 lint/detekt (`build-logic`) | No shared files except `settings.gradle.kts` (AI-03) and `libs.versions.toml` (append-only) |
| **2** | DOC-02 + DOC-03 + DOC-10 autosave/index (`core:*`, editor wiring) · CANVAS-01 + CANVAS-02 erasers (`engine:canvas`) · SPEN-02 + SPEN-05 + SPEN-06 palm/tilt/pressure · LIB-01 + LIB-03 (`feature:library`) · PAGES-01 + PAGES-02 · DS-03 toolbar | Needs wave 1 merged |
| **3** | CANVAS-03..07 zoom/tiles/pagination · LIB-02 · DS-04 pen picker · INK-02 + INK-04..06 · DOC-04 · AI-05 + AI-06 renderer/measurer | M1 exit after this wave |
| **4+** | M2 tasks from each workstream file, in ID order | |

## Shared-file rules (where parallel agents collide)
- `gradle/libs.versions.toml`, `settings.gradle.kts`: **append only**, one line per entry, keep sections. Resolve conflicts by keeping both sides.
- `core:model`: add new types in **new files**. Changing an existing type is a contract change, so put it in its own PR first.
- Never reformat files you didn't otherwise change.
- Tick your task box in `docs/workstreams/NN-*.md` in the same PR.

## Saving tokens
- Read the workstream file and only the modules you own. Don't explore the whole repo.
- Run module-scoped `verify.sh`, not the full build. Run the full `--app` build once at the end.
- Use the `-q` Gradle output. On failure, read only the first error.
- Keep the final report to 5 lines: task, what changed, test count, device check, PR URL.
