# Contributing to BestNote

Thanks for helping build the best notes app on Android. This page covers everything you need to ship your first PR.

## 1. Set up
- Android Studio (latest stable), JDK 17, Android SDK platform 37.
- A stylus device is strongly recommended. Most bugs that matter only show up with a real pen.

```bash
./gradlew assembleDebug        # build
./gradlew test                 # unit tests (JVM)
./gradlew :app:installDebug    # install on a connected device
```

## 2. Find work
1. Read [`docs/workstreams/README.md`](docs/workstreams/README.md) and pick a workstream.
2. Take the lowest open task ID whose dependencies are done.
3. Open an issue with the **Task** template (`[INK-03] Highlighter`) and assign yourself, so nobody else picks it up.
4. Large (L) tasks: post a short plan in the issue first. XL tasks must be split before anyone starts them.

## 3. Branches, commits, PRs
- Branch: `ws/<prefix>-<nn>-short-name` (e.g. `ws/spen-04-side-button`). Fixes: `fix/<short-name>`.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/) with the module as scope:
  `feat(engine-ink): add highlighter brush`, `fix(stylus): ignore canceled palm pointers`.
- **One task per PR.** Fill in the PR template, link the task ID, and add a screenshot or video for anything visual.
- Keep PRs under ~400 changed lines where possible. Split refactors from features.

## 4. Code rules
- Respect the module dependency rules in [`docs/architecture/overview.md`](docs/architecture/overview.md#dependency-rules-enforced-by-qa-02). No `feature` → `feature` dependencies.
- New dependencies go in `gradle/libs.versions.toml` only, and must be open source with a license compatible with the project. Anything proprietary (Play Services, Samsung SDKs) goes behind a build flavor. Ask in the issue first.
- UI uses theme tokens and `Motion` tokens only (see [design principles](docs/design/design-principles.md)).
- No work on the main thread that scales with document size.
- Pure logic goes in JVM modules with unit tests.
- Architecture changes need an ADR in `docs/architecture/adr/` (copy `0000-template.md`).

## 5. Definition of done
- [ ] "Done when" criteria in the workstream table are met
- [ ] Unit tests for testable logic; `./gradlew test` passes
- [ ] Checked on a real stylus device (say which one in the PR)
- [ ] Light/dark, RTL and large-screen layouts checked for UI changes
- [ ] Docs updated if behavior or format changed; task box ticked in the workstream file

## 6. Reviews
- Every PR needs one approval from the owner of each module it touches (see `.github/CODEOWNERS`).
- Reviewers check ink and canvas changes on a device before approving.
