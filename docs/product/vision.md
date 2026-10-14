# Product Vision

## One line
The best handwriting notes app on Android: free, open source, works offline, and feels better with a Samsung S Pen than anything else on the platform.

## Who it is for
1. **Students** on Galaxy Tab S, taking lecture notes and marking up PDF slides.
2. **Professionals** who sign, review and annotate PDFs and keep meeting notebooks.
3. **Artists and planners** who care about how the ink feels and how the page looks.

## What "better than Goodnotes" means
We will not win by matching a feature list. We win by beating them where they are weak on Android:

| Pillar | What we commit to | How we measure it |
|---|---|---|
| **Ink feel** | Ink stays under the pen tip. Pressure, tilt and smoothing feel tuned for the S Pen, not ported from iPad. | Pen-to-pixel latency ≤ 1 frame on 120 Hz Tab S devices (front-buffered). Zero dropped frames while writing. |
| **S Pen first** | Side button, hover, eraser gestures, Air actions and palm rejection behave the way Samsung Notes users expect. | Every item in [`../platform/samsung-s-pen.md`](../platform/samsung-s-pen.md) passes on the device matrix. |
| **Beautiful and calm UI** | Material 3, but quieter: chrome gets out of the way, motion explains state changes, nothing jumps. | Design review sign-off for every screen; motion tokens only, no ad-hoc durations. |
| **Fast with big documents** | A 500-page PDF opens instantly and scrolls smoothly. | Cold open < 1 s, scroll jank < 1% frames (Macrobenchmark). |
| **Smart Board** | Photograph a whiteboard and get the notes an expert student would write: logical order, ruler-straight diagrams, meaningful colors, proper LaTeX. | Scored on a 50-photo evaluation set (AI-09). |
| **Yours, forever** | No account, no tracking, no ads, no paywall. Open file format. Export everything. | No network permission in the core build. Documented `.bnote` format. |

## Principles
- **Local-first.** Everything works offline. Optional sync later goes through storage the user owns (a folder they pick, WebDAV, Nextcloud). Never our servers.
- **Pen before finger.** When a stylus is present, the finger pans and zooms and the pen writes. Never the other way round by accident.
- **No modal friction.** Tools switch in one tap or one button press. Settings live one tap away on the tool itself.
- **Every pixel earns its place.** If a control doesn't help writing, it hides.
- **Arabic and RTL are first class.** UI and handwriting search work for Arabic from day one.

## Non-goals (for v1)
- Accounts, cloud sync, real-time collaboration, sharing links.
- Cloud AI chat and summaries. The one exception is **Smart Board** (board photo → notes): opt-in, bring your own key, never in the `foss` build ([ADR-0007](../architecture/adr/0007-optional-cloud-ai.md)).
- iOS, desktop or web clients.
- A template or sticker marketplace.

## Success at v1.0
- 4.6★ or better on Play and F-Droid.
- Samsung tablet users say it beats Goodnotes on Android for ink feel, which is our headline claim.
- Contributors can pick up a task from [`../workstreams/`](../workstreams/) and ship it without asking how the project works.
