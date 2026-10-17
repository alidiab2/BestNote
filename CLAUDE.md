# BestNote: notes for AI agents

Open-source Android handwriting notes app (Kotlin, Compose, androidx.ink). Read `README.md` first.

## Working on a task
- Tasks live in `docs/workstreams/NN-*.md` with IDs like `INK-03`. Work on one task at a time, inside the modules that workstream owns (see `docs/workstreams/README.md`).
- Changing a cross-workstream contract (types in `:core:model`, `StylusEvent`, `NotebookStore`, `PdfDocumentSource`, recognition interfaces, `Motion` tokens) means updating every caller and saying so in the PR.
- Tick the task box in the workstream file when the task is done.

## Rules
- Module dependencies: `app → feature → engine/ai → core`. No `feature` → `feature`. `core:model`, `core:common`, `core:storage`, `engine:recognition`, `engine:layout`, `engine:mathtype`, `ai:vision-api` and `ai:math` stay pure JVM.
- Smart Board AI: `Prompts.EXPERT_STUDENT` is the foundational persona. Behavior it asks for must also be enforced in `:engine:layout` (see `docs/workstreams/12-smart-board-ai.md`).
- Versions only in `gradle/libs.versions.toml`. Shared build config in `build-logic/convention` (`bestnote.*` plugins).
- UI: theme and `Motion` tokens from `:core:designsystem` only. Nothing animates under an active pen.
- Never block the main thread with work that grows with document size.
- S Pen facts marked **[verify]** in `docs/platform/samsung-s-pen.md` are unconfirmed. Test on a device before relying on them.

## Build (this Mac)
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug test
./gradlew --stop   # free RAM afterwards
```
Don't install over or uninstall an existing build on the user's devices without asking.
