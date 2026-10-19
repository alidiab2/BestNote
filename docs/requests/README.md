# In-app requests

Users write feature requests, improvements and bugs in **Settings, Requests**, with up to 4 screenshots.

- Always saved on the device first, in `Android/data/<app id>/files/requests/` (`requests.json`, `requests.md`, `images/`).
- With a GitHub token set in the same screen, each one is also sent as an issue to a private repo
  (default `alidiab2/BestNote-requests`, label `app-request` plus `feature`, `improvement` or `bug`).
  Screenshots are committed under `attachments/` in that repo and embedded in the issue.
- Closing the issue on GitHub marks the request done in the app the next time the screen opens.

## Working through them

```bash
scripts/pull-requests.sh          # open issues + screenshots from GitHub -> docs/requests/requests.md, docs/requests/images/
scripts/pull-requests.sh --adb    # the device's local copy (USB debugging), e.g. requests that were never sent
```

Read `requests.md` and look at the images, take an issue, fix it, and finish the commit message with
`Fixes alidiab2/BestNote-requests#N` so the issue closes when it lands on `main`. The pulled files are a
snapshot and are git-ignored.

## Token

The token lives only in the app, sealed with an Android Keystore key. Make it a fine-grained token limited to
the requests repo with **Issues** and **Contents** read and write; it can then touch nothing else, including
this source repo.
