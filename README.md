<p align="center">
  <img src="docs/assets/banner.svg" alt="BestNote: a great handwriting notes app for Android students" width="100%">
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-2ea44f"></a>
  <a href="https://github.com/alidiab2/BestNote/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/alidiab2/BestNote?include_prereleases&label=latest%20build"></a>
  <img alt="Platform: Android" src="https://img.shields.io/badge/platform-Android-3ddc84">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7f52ff">
  <a href="CONTRIBUTING.md"><img alt="PRs welcome" src="https://img.shields.io/badge/PRs-welcome-ff69b4"></a>
  <a href="#بالعربي"><img alt="بالعربي" src="https://img.shields.io/badge/%D8%A8%D8%A7%D9%84%D8%B9%D8%B1%D8%A8%D9%8A-README-blue"></a>
</p>

<h3 align="center">The note-taking app Android students deserve.<br>Free, open source, offline, and built around the pen.</h3>

<p align="center">
  <a href="https://github.com/alidiab2/BestNote/releases/latest"><b>Download the latest build</b></a> ·
  <a href="docs/product/vision.md">Vision</a> ·
  <a href="docs/roadmap.md">Roadmap</a> ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>

<p align="center">
  <img src="docs/assets/editor.png" alt="BestNote editor on a tablet: pens, colors, lasso, shapes, Smart Board, with Arabic, Hebrew and English on the page" width="900">
</p>

## Our goal

**Build a really good notes app for students on Android.**

Students on iPad have excellent handwriting apps. On Android there are very few good ones: most are closed, tied to one brand, or feel like ports. BestNote is an open, community-built answer. We want a student with a Galaxy Tab (or any Android tablet or phone) to run a whole semester in it: lectures, PDF slides, formulas, diagrams, and revision, in any language, without an account, ads or a subscription.

If you are a student, a developer, a designer, or someone who just loves good tools, **this project is for you and we want your help.**

## What makes it different

<table>
<tr>
<td width="50%" valign="top">

### Ink that feels right
Pressure-sensitive pens (fountain, ball, brush, pencil, highlighter), tuned for the Samsung S Pen: low latency, palm rejection, hover cursor, side-button eraser.

### Arabic and RTL are first class
Arabic, Hebrew and English side by side on one page. Right-to-left UI and a font picker that understands Arabic scripts.

### Your notes stay yours
No account, no ads, no tracking, no paywall. Works fully offline. Open file format and export.

</td>
<td width="50%" valign="top">

### Smart Board
Photograph a whiteboard and get clean, ordered study notes with straight diagrams, meaningful colors and equations. Opt-in and bring your own AI key; the core app never needs the network.

### Built for studying
Equations, shapes that stay connected when you move them, lasso selection, a page manager and dark mode. PDF annotation and more are on the [roadmap](docs/roadmap.md).

### Open and hackable
Modular Kotlin and Jetpack Compose codebase with documented architecture, numbered tasks and a clear contribution flow.

</td>
</tr>
</table>

## See it

### Smart Board: whiteboard photo in, study notes out
Real output from the current builds, on an Arabic calculus lecture.

<table>
<tr>
<td align="center" width="50%"><img src="docs/assets/smartboard-before.png" alt="Smart Board result while it is still writing the page" width="100%"><br><sub>It writes the page the way a student would</sub></td>
<td align="center" width="50%"><img src="docs/assets/smartboard-after.png" alt="Finished Smart Board page with headings, colors, equations and a vocabulary list" width="100%"><br><sub>Finished page: headings, colors, equations, glossary</sub></td>
</tr>
</table>

### Everything you need on the page
<table>
<tr>
<td align="center" width="50%"><img src="docs/assets/arabic-fonts.png" alt="Font picker with Arabic, Latin and Hebrew fonts" width="100%"><br><sub>Fonts for Arabic, Latin and Hebrew</sub></td>
<td align="center" width="50%"><img src="docs/assets/equations.png" alt="Typeset equations: quadratic formula, integrals, sums, matrices" width="100%"><br><sub>Typeset equations</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/assets/shapes.png" alt="Shapes with arrows that stay glued when moved" width="100%"><br><sub>Shapes and connectors that follow when you move them</sub></td>
<td align="center"><img src="docs/assets/page-manager-dark.png" alt="Page manager in dark mode" width="100%"><br><sub>Page manager, dark mode</sub></td>
</tr>
</table>

<p align="center">
  <img src="docs/assets/library-dark.png" alt="Notebook library in dark mode" width="800"><br>
  <sub>The notebook library in dark mode. Dark mode is a first-class citizen.</sub>
</p>

## Status

**Early and moving fast.** The app already builds, writes, and ships test builds (latest: see [Releases](https://github.com/alidiab2/BestNote/releases)). It is **not 1.0 yet**: expect rough edges. Milestones and what is done are in the [roadmap](docs/roadmap.md).

## Try it

1. Go to [Releases](https://github.com/alidiab2/BestNote/releases/latest) and download the `.apk`.
2. Allow "Install unknown apps" for your browser or file manager when Android asks.
3. Open BestNote and start writing. Best on a Galaxy Tab with an S Pen, but any Android device works.

## Help us build it

You do not need to ask permission, just pick something:

- **Pick a task.** [`docs/workstreams/`](docs/workstreams/) has 12 workstreams with numbered, sized tasks (like `INK-03`) and clear "done" criteria.
- **Test and report.** Try a build on your device and open an issue. Different tablets and S Pen models are very valuable.
- **Design.** Interactive mockups live in [`design/`](design/). Propose improvements.
- **Translate.** Arabic is in; more languages are welcome.
- **Students: tell us what you need.** Real study workflows decide what we build next.

Start with [CONTRIBUTING.md](CONTRIBUTING.md). Questions and ideas are welcome in Issues.

## Build from source

Requires JDK 17 and Android SDK platform 37.

```bash
git clone https://github.com/alidiab2/BestNote.git
cd BestNote
./gradlew assembleDebug test
./gradlew :app:installDebug
```

## Project map

| Where | What |
|---|---|
| [`docs/product/`](docs/product/) | Vision, Goodnotes → BestNote feature matrix |
| [`docs/roadmap.md`](docs/roadmap.md) | Milestones M0–M4, exit criteria, open decisions |
| [`docs/workstreams/`](docs/workstreams/) | **Start here to pick work:** 12 workstreams with numbered tasks |
| [`docs/architecture/`](docs/architecture/) | Module layout, stroke pipeline, file format, ADRs |
| [`docs/platform/samsung-s-pen.md`](docs/platform/samsung-s-pen.md) | S Pen / Galaxy tablet spec + device matrix |
| [`docs/design/`](docs/design/) | UI, motion and haptics principles |
| [`docs/release.md`](docs/release.md) | How builds are published and how in-app updates work |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Workflow, branches, definition of done |

<details>
<summary><b>Modules</b></summary>

| Layer | Modules |
|---|---|
| App | `:app` |
| Features | `:feature:library` · `:feature:editor` · `:feature:settings` · `:feature:smartboard` · `:feature:onboarding` · `:feature:templates` |
| Engine | `:engine:canvas` · `:engine:ink` · `:engine:stylus` · `:engine:pdf` · `:engine:recognition` · `:engine:layout` · `:engine:templates` · `:engine:export` |
| AI (opt-in) | `:ai:vision-api` · `:ai:vision-providers` · `:ai:math` · `:ai:board-scan` |
| Core | `:core:model` · `:core:common` · `:core:storage` · `:core:database` · `:core:datastore` · `:core:designsystem` · `:core:ui` · `:core:updater` |

Dependency direction: `app → feature → engine → core`. Details in the [architecture overview](docs/architecture/overview.md).

</details>

## License

[MIT](LICENSE). Use it, learn from it, fork it, improve it.

---

<div dir="rtl">

## بالعربي

**BestNote** تطبيق ملاحظات بخط اليد للأندرويد، مجاني ومفتوح المصدر ويشتغل بدون إنترنت.

### هدفنا
نعمل **تطبيق أندرويد منيح للطلاب**. على الآيباد في تطبيقات ممتازة للكتابة بخط اليد، لكن على الأندرويد التطبيقات المنيحة قليلة جداً: أغلبها مغلق، أو لشركة وحدة بس، أو شكله منقول من نظام ثاني. بدنا طالب معه تابلت سامسنغ (أو أي جهاز أندرويد) يقدر يمشّي فصل دراسي كامل عليه: محاضرات، شرائح PDF، معادلات، رسومات، ومراجعة، بأي لغة، بدون حساب ولا إعلانات ولا اشتراك.

### ليش BestNote مختلف
- **كتابة بتحسها طبيعية:** أقلام حساسة للضغط ومظبوطة لقلم S Pen.
- **العربي والـ RTL أساس مش إضافة:** عربي وعبري وإنجليزي بنفس الصفحة.
- **Smart Board:** صوّر السبورة والذكاء الاصطناعي يكتبها مثل أشطر طالب بالصف: بترتيب الشرح، رسومات مستقيمة، ألوان لها معنى، ومعادلات. اختياري وبمفتاحك أنت.
- **ملاحظاتك إلك:** بدون حساب، بدون إعلانات، بدون تتبّع، وبدون إنترنت.
- **مفتوح للجميع:** كود منظّم، مهام مرقّمة، وطريقة مساهمة واضحة.

### جرّبه
نزّل ملف الـ `.apk` من صفحة [Releases](https://github.com/alidiab2/BestNote/releases/latest) وثبّته. التطبيق لسا بمراحله الأولى وما وصل 1.0، فتوقع شوية أخطاء.

### كيف تساعد
1. افتح [`docs/workstreams/README.md`](docs/workstreams/README.md). فيه 12 مسار عمل، ولكل مسار مهام مرقّمة (مثل `INK-03`) بحجم وشرط انتهاء واضح.
2. خذ أول مهمة مفتوحة، وافتح لها issue، واشتغل على فرع اسمه `ws/ink-03-...`.
3. جرّب التطبيق على جهازك وبلّغنا عن أي مشكلة. اختلاف الأجهزة والأقلام مفيد كثير.
4. إذا كنت طالب، قلنا شو بتحتاج. طريقة دراستك الحقيقية هي اللي بتحدد شو نبني بعدين.

المراحل: **M1** الكتابة تكون ممتازة · **M2** استخدام يومي (PDF، lasso، مجلدات) · **M3** إصدار 1.0 (بحث بخط اليد عربي وإنجليزي، طبقات، صوت) · **M4** ميزات أقوى.

</div>
