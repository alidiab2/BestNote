# BestNote

**The best handwriting notes app on Android.** Free, open source and offline, built for the Samsung S Pen and Galaxy tablets.

> Status: **M0, walking skeleton.** The project builds and draws pressure-sensitive ink. Everything else is planned in the [roadmap](docs/roadmap.md).

## Why
Goodnotes on Android is a port. Samsung Notes is closed and Samsung-only. BestNote aims to be:
- **The best ink on Android:** front-buffered, predicted, ≤ 1 frame latency, pens tuned for the S Pen.
- **S Pen first:** palm rejection, hover cursor, side-button eraser, DeX, multi-window, Air actions.
- **Beautiful and calm:** Material 3, quiet chrome, physical motion.
- **Smart Board:** photograph a whiteboard and get the notes the best student in the room would write.
- **Yours:** no account, no ads, no tracking, an open file format, export everything.

Read the full [product vision](docs/product/vision.md).

## Project map

| Where | What |
|---|---|
| [`docs/product/`](docs/product/) | Vision, Goodnotes → BestNote feature matrix |
| [`docs/roadmap.md`](docs/roadmap.md) | Milestones M0–M4, exit criteria, open decisions |
| [`docs/workstreams/`](docs/workstreams/) | **Start here to pick work:** 12 workstreams with numbered tasks |
| [`docs/architecture/`](docs/architecture/) | Module layout, stroke pipeline, file format, ADRs |
| [`docs/platform/samsung-s-pen.md`](docs/platform/samsung-s-pen.md) | S Pen / Galaxy tablet spec + device matrix |
| [`docs/design/`](docs/design/) | UI, motion and haptics principles |
| [`docs/research/goodnotes/`](docs/research/goodnotes/) | Goodnotes feature research (source material) |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Workflow, branches, definition of done |
| [`docs/agents/`](docs/agents/) | Running many AI agents in parallel: waves, worktrees, scripts |

## Modules

| Layer | Modules |
|---|---|
| App | `:app` |
| Features | `:feature:library` · `:feature:editor` · `:feature:settings` |
| Engine | `:engine:canvas` · `:engine:ink` · `:engine:stylus` · `:engine:pdf` · `:engine:recognition` · `:engine:layout` |
| AI (opt-in) | `:ai:vision-api` · `:ai:math` |
| Core | `:core:model` · `:core:common` · `:core:storage` · `:core:database` · `:core:datastore` · `:core:designsystem` · `:core:ui` |

Dependency direction: `app → feature → engine → core`. Details in [architecture overview](docs/architecture/overview.md).

## Build

Requires JDK 17 and Android SDK platform 37.

```bash
./gradlew assembleDebug test
./gradlew :app:installDebug
```

## License
To be decided (see [roadmap, D1](docs/roadmap.md#open-decisions)).

---

## بالعربي

**BestNote** تطبيق ملاحظات بخط اليد للأندرويد، مجاني ومفتوح المصدر ويشتغل بدون إنترنت. هدفه يكون أقوى من Goodnotes على أندرويد، وخصوصاً مع قلم S Pen وتابلت سامسنغ.

**كيف نوزّع الشغل:**
1. افتح [`docs/workstreams/README.md`](docs/workstreams/README.md). فيه 12 مسار عمل، ولكل مسار ملفات (modules) خاصة فيه، عشان ما حدا يدوس على شغل غيره.
2. كل مسار فيه مهام مرقّمة (مثل `INK-03`)، ولكل مهمة حجم ومرحلة (M1–M4) وشرط انتهاء واضح.
3. خذ أول مهمة مفتوحة، وافتح لها issue، واشتغل على فرع اسمه `ws/ink-03-...`.
4. المسار الأهم هو **الحبر + قلم S Pen + الكانفاس** (INK → SPEN → CANVAS). هذا قلب التطبيق.
5. ميزة **Smart Board** (المسار 12): تصوّر اللوح، والذكاء الاصطناعي يكتبه مثل أشطر طالب بالصف: بترتيب الشرح المنطقي، ورسومات بالمسطرة والفرجار، وألوان لها معنى، ومعادلات LaTeX.

المراحل: **M1** الكتابة تكون ممتازة · **M2** استخدام يومي (PDF، lasso، مجلدات) · **M3** إصدار 1.0 (بحث بخط اليد عربي وإنجليزي، طبقات، صوت) · **M4** ميزات أقوى من Goodnotes.
