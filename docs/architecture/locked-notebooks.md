# Locked notebooks (LIB-11)

Status: implemented. Code: `:core:storage` (`SealedNotebooks.kt`, `FileNotebookStore`), `:core:security`
(`LockManager`, `AndroidKeyVault`, `LockUi`), wired in `:feature:library` and `:feature:editor`.

## What is protected
A locked notebook's pages, ink, text, images, PDFs, audio and extras are **encrypted at rest**, including the
working files outside its package (recordings, PDF scratch; see LIB-11b below). What stays readable is
what the locked cover shows: the title, dates, template and page count (the plaintext stub manifest). Locked notebooks
never get a cached thumbnail or search-index rows (both are plaintext on disk); locking one deletes those it had.

## Keys
```
data key (DEK)      random 256-bit, one per notebook
  ├─ enc key       = HMAC-SHA256(DEK, "bestnote/lib-11/enc")    AES-256-GCM per file
  ├─ name key      = HMAC-SHA256(DEK, "bestnote/lib-11/names")  file names
  └─ keyCheck      = HMAC-SHA256(DEK, "bestnote/lib-11/check")  first 16 bytes, in the stub (spots a wrong key)

wraps (in the stub's header, any of):
  device    RSA-2048 OAEP (SHA-256) Android Keystore key "bestnote.lock.device.v1", setUserAuthenticationRequired,
            usable 30 s after the device credential or a strong biometric (BiometricPrompt, BIOMETRIC_STRONG |
            DEVICE_CREDENTIAL); not invalidated by new fingerprints
  pin       devices without a screen lock: Keystore key "bestnote.lock.pin.v1" (no auth possible) wrapping
            AES-GCM(pinKey, DEK); pinKey = HMAC(PBKDF2-SHA256(PIN, salt, 120k), "pin-wrap"). The app PIN is set once;
            only salt + HMAC(master, "pin-verify") are stored (never the PIN)
  password  optional recovery password: AES-GCM(PBKDF2-SHA256(password, salt, 120k), DEK); one salt per lock action,
            so a folder locked at once unlocks with one derivation
```
Wrapping uses the RSA public key in software, so locking never needs a prompt (e.g. a notebook moved into a locked
folder); only unwrapping does. Unlocked DEKs live only in memory (`NotebookKeyring`).

## Lifecycle
- **Lock**: wait for the editor's last plain save, then `FileNotebookStore.seal` writes the whole notebook encrypted into
  `.seal-<id>/`, renames the plain directory to `.old-<id>`, the sealed one into place, and deletes the old one. A crash
  in between is rolled back (old plain notebook restored) or forward (sealed kept) on the next start.
- **Unlocked session**: reads decrypt in memory; every save is encrypted (whole pages: the DOC-11 journal holds
  plaintext strokes, so `appendJournal` returns null for sealed notebooks). Without the key, `read`/`write` throw
  `NotebookLockedException` before touching a file: nothing is ever written in plain while locked.
- **Relock**: after the app has been in the background for the user's setting (immediately / 1 min / 5 min / until the
  app closes; default 1 min). Open editors flush first; a rotation or the system credential screen does not count.
- **Remove lock**: the same swap in the other direction (`unseal`); every byte of the package is kept (tested).
- **Trash**: a locked notebook moves to and from the Trash as it is, still encrypted (no unlock needed).
- **Export / backup**: export needs an unlock and says the file is not encrypted. Backup includes a locked notebook
  only while it is unlocked (as a plain `.bnote`); otherwise its earlier backed-up copy is kept.
- **Screen**: the editor of a locked notebook sets `FLAG_SECURE` (no screenshots, blank recents preview); the library
  draws a lock on the cover and leaves it out of search, Continue writing and Recent until unlocked.

## Old apps
The stub is format 9 (plain notebooks stay at 8), so an older BestNote refuses the notebook ("made by a newer
version") instead of misreading or overwriting it, and skips it in its library list. Encrypted files never parse as
JSON or ink chunks. Every plain reading path of this app throws `NotebookLockedException` on a stub.

## If the device lock changes
Removing the screen lock (or a Keystore reset) makes Android delete the auth-bound key: notebooks locked with the
device key then open only with their recovery password, which the lock dialog offers and explains (en + ar). Changing
the PIN/pattern keeps the key. Notebooks locked with the app PIN survive screen-lock changes but not "Clear data".

## Working files outside the package (LIB-11b)
Nothing of a locked notebook is written in plain outside its package, also while it is unlocked:

- **Voice recordings** (`files/audio/<id>/`). A locked notebook's working copies are `SealedStream` files
  (`SealedStream.kt`): chunked AES-256-GCM under the notebook's enc key, 16 KiB plaintext per record, nonce =
  8-byte random prefix | u32 index, AAD = `aad(id, "work/audio/<rec>")` | index | final flag. While recording,
  MediaRecorder writes ADTS AAC (a pipe cannot seek, so not MP4) into a pipe; a thread encrypts it record by record
  into `<rec>.bnsr`, renamed `<rec>.bnsa` on stop (`SealedSink`). A crash keeps every whole record, still encrypted;
  the next editor open adopts what is valid (the final-flag tells cut-off from complete; a torn tail is dropped).
  Playback decrypts one record at a time through a `MediaDataSource`; the player closes when the notebook relocks.
  The service gets the keys from the editor in memory (`RecorderHub.handKeys`) and keeps them only while recording,
  so a recording running in the background keeps going after a relock. Locking encrypts existing plain copies
  (`LockHooks.sealedWith`); removing the lock writes them back plain (`LockHooks.unsealed`); unlocking encrypts any
  plain copy an older build left (`LockedScratch.follow`).
- **PDF backgrounds.** Opened with `PdfNotebookFiles.openPrivate`: a memfd on Android 11+, else a file in
  `no_backup/locked-pdf/<id>/` that is unlinked as soon as PdfRenderer holds it. That folder is deleted when the
  editor closes, when the key is dropped (relock / background timeout), and at every app start (crash orphans).
  Exports open PDFs the same way, so `export-pdf-sources` is no longer a plain cache.
- **Derived data.** Thumbnails and search rows were already refused for sealed ids; the indexer now also re-checks
  before each page and, after each run, drops rows and page pictures of every notebook sealed meanwhile. Its plain
  PDF copies (`cache/search-pdf`) live only while one notebook is read. The DOC-11 journal stays off for sealed
  notebooks. Raw AI answers (`diagnostics/ai-exchanges.json`) are not captured while any locked notebook is unlocked.
  Camera shots (editor and Smart Board) are deleted from the cache once read into the notebook.
- **Start-up sweep** (`BestNoteApplication.keepLockedFilesPrivate`): `no_backup/locked-pdf`, older builds' plain caches
  (`cache/pdf/<sealed id>`, `export-pdf-sources`, `search-pdf`, an interrupted export's `.sources`) and plain
  recording parts of sealed notebooks. A newly locked notebook's `cache/pdf` copy goes as soon as it is sealed.

Not covered: an export the user makes (with the plain-export warning) is plain by design and stays in
`cache/exports` until the next export; crash logs hold stack traces only.

## Known limits
- Titles, dates and page counts stay readable (the cover needs them); file sizes reveal roughly how much is written.
- A locked notebook's new recordings are ADTS AAC stored under the `.m4a` asset name (players sniff the content).
  The package still holds each recording as one sealed entry, read whole when the notebook opens (as before).
- Deleting the plain copy after locking cannot scrub flash storage; locking protects what is written from then on.
- On Android 10 (API 29) the prompt allows weak biometrics, which do not unlock the Keystore key; the screen lock does.
