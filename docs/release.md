# Releases and in-app updates (REL-01)

The source repo is private. Builds are published, APK only, to the **public** repo
`alidiab2/BestNote-releases`. The app reads that repo's releases without a token.

## Version numbers (built automatically in `app/build.gradle.kts`)
- `versionCode` = number of commits on `HEAD` (`git rev-list --count HEAD`). It grows with every merge.
- `versionName` = `0.1.<count>-<shortsha>` (7 chars).
- Without git, or in a shallow clone (CI default), `versionCode` falls back to `$BESTNOTE_VERSION_CODE`, else `1`.
  CI checks out with `fetch-depth: 0`. Override locally with `-PversionCodeOverride=N`.
- `BuildConfig.VERSION_NAME`, `VERSION_CODE`, `GIT_COMMIT` are available in `:app`.
- Debug `applicationId` stays `app.bestnote.debug`.

## Release convention (what the updater parses)
A GitHub release in `BestNote-releases` with:
1. an `.apk` asset (first one is used; `https` URL),
2. the version code in the **body** as its own line: `versionCode: 123`
   (also accepted: `version code = 123`, `**versionCode:** 123`),
3. otherwise the **tag** `v0.1.<count>[-sha]`, where `<count>` is the versionCode.

Drafts, releases with no APK, and releases whose code cannot be read are ignored. The newest is the
one with the highest versionCode; prereleases count. The `versionCode:` line is hidden from the notes
the user sees; the rest of the body is shown as release notes (headings, bullets, `**bold**`).

## Publishing
`scripts/release.sh [--prerelease] [--notes "..."|--notes-file f] [--dry-run]` builds the debug APK on this Mac
(same `~/.android/debug.keystore` every time, so the APK installs as an update and keeps data), names it
`BestNote-<versionName>.apk` and runs `gh release create` with tag `v<versionName>` and the body above.
It refuses a dirty tree. Use `--dry-run` to see what it would publish.

## In the app
- On start, at most once per 24 h and only if Settings → About → Updates → "Check automatically" is on.
  "Check for updates" always asks.
- A newer versionCode opens a dialog: Update now / Later (asks again after 24 h) / Skip this version
  (stores the skipped versionCode; a still newer release prompts again). A manual check ignores Later/Skip.
- Update now downloads to `cache/updates` with progress, checks the size equals the asset size, then starts the
  system installer through a `FileProvider`. If "Install unknown apps" is off for BestNote, the settings screen
  opens; the user returns and taps Update again.
- Android keeps the data only when package name and signing key match the installed app.
