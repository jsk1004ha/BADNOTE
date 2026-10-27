# Language-aware Scribble Erase Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make English and Portuguese use a stricter left-right scratching eraser gesture while preserving the existing mixed circular scribble behavior for Korean, Japanese, and Chinese.

**Architecture:** `scribbleGestureProfile` becomes a language-aware router. A pure Latin scratch classifier measures full-width X traversals in screen coordinates, while the current classifier moves unchanged into a mixed-language helper. Existing object/path hit testing remains the only deletion boundary.

**Tech Stack:** Vanilla JavaScript editor, Python Playwright regression harness, Android WebView assets, Gradle Android application, GitHub Releases.

---

## File map

- Modify `tools/test_web.py`: browser-level language-aware scribble regression cases.
- Modify `web/app.js`: Latin scratch classifier, language routing, diagnostic exports.
- Modify `web/native-bridge.js`: version and multilingual in-app release notes.
- Modify `web/upgrade32.js`: version metadata.
- Modify `web/index.html`: displayed version.
- Modify `android/app/build.gradle`: Android `versionCode` and `versionName`.
- Modify `android/app/src/main/java/com/inkforge/notesstudio/MainActivity.java`: native updater version.
- Replace `android/app/src/main/assets/public/` from `web/` using `copy_script.ps1`.
- Modify `README.md`, `docs/README-KO.md`, and `docs/INSTALL-KO.txt`: release references.
- Create `docs/CHANGELOG-3.3.27-KO.md`: release verification record.

### Task 1: Lock language-specific behavior with a failing browser regression

**Files:**
- Modify: `tools/test_web.py` after the current scribble regression cases.

- [ ] **Step 1: Add an integration test with real pointer events**

Add one `language_aware_scribble_erase` result that creates a touched target and nearby guard, switches `api.state.settings.language`, and dispatches real pen pointer paths:

```javascript
results["language_aware_scribble_erase"] = await page.evaluate("""
  async () => {
    const api = window.__inkforge;
    const pageIndex = api.state.currentPageIndex;
    const notePage = api.currentPage();
    const originalLanguage = api.state.settings.language;
    const baseIds = new Set(notePage.objects.map((object) => object.id));
    api.setZoom(1);
    api.setTool('pen');
    api.state.settings.scribbleErase = true;
    api.renderPageCanvas(pageIndex);
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const canvas = document.querySelector(`.page-canvas[data-page-index="${pageIndex}"]`);
    const clientFor = (point) => {
      const rect = canvas.getBoundingClientRect();
      return { x: rect.left + point.x / 1000 * rect.width, y: rect.top + point.y / 1414 * rect.height };
    };
    const interpolate = (anchors, samples = 3) => anchors.flatMap((start, index) => {
      if (index === anchors.length - 1) return [start];
      const end = anchors[index + 1];
      return Array.from({ length: samples }, (_, step) => {
        const t = step / samples;
        return { x: start.x + (end.x - start.x) * t, y: start.y + (end.y - start.y) * t };
      });
    });
    const horizontalScratch = interpolate([
      { x: 438, y: 491 }, { x: 562, y: 509 }, { x: 440, y: 493 },
      { x: 560, y: 507 }, { x: 442, y: 495 }, { x: 558, y: 505 }
    ], 4);
    const repeatedLoops = Array.from({ length: 73 }, (_, index) => {
      const angle = index / 24 * Math.PI * 2;
      return { x: 500 + Math.cos(angle) * 62, y: 500 + Math.sin(angle) * 22 };
    });
    const cursiveProgression = Array.from({ length: 49 }, (_, index) => ({
      x: 438 + index * 2.6,
      y: 500 + Math.sin(index / 2.1) * 19
    }));
    const sendPath = (pointerId, path) => {
      const clients = path.map(clientFor);
      const send = (type, point) => canvas.dispatchEvent(new PointerEvent(type, {
        bubbles: true, cancelable: true, pointerId, pointerType: 'pen', isPrimary: true,
        button: 0, buttons: type === 'pointerup' ? 0 : 1,
        pressure: type === 'pointerup' ? 0 : .55, clientX: point.x, clientY: point.y
      }));
      send('pointerdown', clients[0]);
      clients.slice(1).forEach((point) => send('pointermove', point));
      send('pointerup', clients[clients.length - 1]);
    };
    const runCase = async (name, language, path, expectErased, pointerId) => {
      notePage.objects = notePage.objects.filter((object) => baseIds.has(object.id));
      const targetId = `language_scribble_${name}_target`;
      const guardId = `language_scribble_${name}_guard`;
      notePage.objects.push({
        id: targetId, type: 'stroke', brush: 'fountain', color: '#111827', width: 7, opacity: 1,
        points: [{ x: 435, y: 500, p: .6 }, { x: 500, y: 500, p: .6 }, { x: 565, y: 500, p: .6 }]
      }, {
        id: guardId, type: 'stroke', brush: 'fountain', color: '#111827', width: 7, opacity: 1,
        points: [{ x: 435, y: 558, p: .6 }, { x: 500, y: 558, p: .6 }, { x: 565, y: 558, p: .6 }]
      });
      api.state.settings.language = language;
      sendPath(pointerId, path);
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const targetErased = !notePage.objects.some((object) => object.id === targetId);
      const guardPresent = notePage.objects.some((object) => object.id === guardId);
      return { targetErased, guardPresent, passed: targetErased === expectErased && guardPresent };
    };
    const cases = {
      enScratch: await runCase('en_scratch', 'en', horizontalScratch, true, 9011),
      ptScratch: await runCase('pt_scratch', 'pt', horizontalScratch, true, 9012),
      enLoops: await runCase('en_loops', 'en', repeatedLoops, false, 9013),
      ptCursive: await runCase('pt_cursive', 'pt', cursiveProgression, false, 9014),
      koLoops: await runCase('ko_loops', 'ko', repeatedLoops, true, 9015)
    };
    notePage.objects = notePage.objects.filter((object) => baseIds.has(object.id));
    api.state.settings.language = originalLanguage;
    api.renderPageCanvas(pageIndex);
    return { cases, passed: Object.values(cases).every((entry) => entry.passed) };
  }
""")
```

- [ ] **Step 2: Run the web regression and verify RED**

Run:

```powershell
python tools\test_web.py --web web --chromium "C:\Users\js100\AppData\Local\ms-playwright\chromium-1228\chrome-win64\chrome.exe" --output .omx\logs\test-web-3.3.27-red.json
```

Expected: exit code 1 and `language_aware_scribble_erase.passed` is false because the existing common classifier erases at least one English/Portuguese looped or cursive-like gesture.

- [ ] **Step 3: Confirm the failure is behavioral**

Read `.omx/logs/test-web-3.3.27-red.json`. The test must reach the editor with no page or JavaScript error; only the new language-aware expectation may fail.

### Task 2: Implement the Latin horizontal scratch classifier

**Files:**
- Modify: `web/app.js` around `scribbleGestureProfile` and the `window.__inkforge` export.

- [ ] **Step 1: Extract the existing classifier without changing it**

Move the current function body verbatim to `mixedScribbleGestureProfile`:

```javascript
function mixedScribbleGestureProfile(points) {
  const metrics = strokeMetrics(points);
  const diagonal = Math.hypot(metrics.bounds.w, metrics.bounds.h);
  const maxDimension = Math.max(metrics.bounds.w, metrics.bounds.h);
  const minDimension = Math.min(metrics.bounds.w, metrics.bounds.h);
  const lengthRatio = metrics.length / Math.max(1, diagonal);
  const intersections = pathSelfIntersections(points);
  const revisit = pathRevisitProfile(points, clamp(diagonal / 7, 12, 24));
  if (latinRLikeGesture(points, metrics, lengthRatio, revisit.revisits)) {
    return { metrics, diagonal, intersections, revisits: revisit.revisits, dense: false, local: false, glyphGuard: 'latin-r' };
  }
  const compact = maxDimension < 560 && minDimension > 8 && diagonal > 24;
  const localCompact = maxDimension <= 180 && minDimension >= 4 && diagonal >= 12;
  const openGesture = metrics.closure > diagonal * .46;
  const scrubbedBackAndForth = metrics.axisReversals >= 4 && metrics.reversals >= 2 && lengthRatio > 2.25;
  const crossedOver = intersections >= 2 && lengthRatio > 1.85;
  const repeatedArea = revisit.revisits >= 4 && metrics.reversals >= 2 && lengthRatio > 2.15;
  const loopedScrub = metrics.axisReversals >= 6 && revisit.revisits >= 5 && lengthRatio > 3;
  const broadDense = compact && metrics.length > Math.max(128, diagonal * 2.45) && (scrubbedBackAndForth || crossedOver || repeatedArea || loopedScrub);
  const localDense = localCompact && metrics.length > Math.max(58, diagonal * 2.05) && (scrubbedBackAndForth || crossedOver || repeatedArea || loopedScrub) && (openGesture || intersections || revisit.revisits >= 3);
  return { metrics, diagonal, intersections, revisits: revisit.revisits, dense: broadDense || localDense, local: localDense && !broadDense };
}
```

- [ ] **Step 2: Add a pure Latin scratch classifier**

Implement the approved relative-threshold algorithm and its deterministic sweep extractor:

```javascript
const LATIN_SCRATCH_LANGUAGES = new Set(['en', 'pt']);

function horizontalScratchSweeps(points, noise) {
  if (!points?.length) return [];
  const sweeps = [];
  let start = points[0], extreme = points[0], direction = 0;
  for (let index = 1; index < points.length; index++) {
    const point = points[index];
    if (!direction) {
      const dx = point.x - start.x;
      if (Math.abs(dx) < noise) continue;
      direction = Math.sign(dx);
      extreme = point;
      continue;
    }
    const extendsRun = direction > 0 ? point.x > extreme.x : point.x < extreme.x;
    if (extendsRun) {
      extreme = point;
      continue;
    }
    const reversal = point.x - extreme.x;
    if (Math.abs(reversal) < noise || Math.sign(reversal) === direction) continue;
    sweeps.push({ start, end: extreme, amplitude: Math.abs(extreme.x - start.x) });
    start = extreme;
    direction = Math.sign(reversal);
    extreme = point;
  }
  if (direction) sweeps.push({ start, end: extreme, amplitude: Math.abs(extreme.x - start.x) });
  return sweeps;
}

function latinScratchGestureProfile(points) {
  const metrics = strokeMetrics(points);
  const bounds = metrics.bounds;
  const diagonal = Math.hypot(bounds.w, bounds.h);
  const lengthRatio = metrics.length / Math.max(1, diagonal);
  const horizontalTravel = (points || []).slice(1).reduce((sum, point, index) => sum + Math.abs(point.x - points[index].x), 0);
  const verticalTravel = (points || []).slice(1).reduce((sum, point, index) => sum + Math.abs(point.y - points[index].y), 0);
  const noise = Math.max(2.5, bounds.w * .025);
  const traversalMinimum = Math.max(9, bounds.w * .22);
  const midpoint = bounds.x + bounds.w / 2;
  const sweeps = horizontalScratchSweeps(points, noise);
  const validSweeps = sweeps.filter((sweep) => sweep.amplitude >= traversalMinimum);
  const midpointSweeps = validSweeps.filter((sweep) => (sweep.start.x - midpoint) * (sweep.end.x - midpoint) <= 0);
  const broadSweeps = validSweeps.filter((sweep) => sweep.amplitude >= bounds.w * .55);
  const dense = points?.length >= 10
    && bounds.w >= 24
    && bounds.h >= 4
    && bounds.w >= bounds.h * 1.15
    && horizontalTravel >= verticalTravel * 3
    && sweeps.length >= 4
    && validSweeps.length >= 3
    && midpointSweeps.length >= 3
    && broadSweeps.length >= 2
    && lengthRatio >= 2.45;
  return {
    metrics,
    diagonal,
    intersections: pathSelfIntersections(points),
    revisits: pathRevisitProfile(points, clamp(diagonal / 7, 12, 24)).revisits,
    dense,
    local: dense && Math.max(bounds.w, bounds.h) <= 180,
    mode: 'latin-scratch'
  };
}
```

`horizontalScratchSweeps(points, noise)` must collapse sub-threshold X jitter, retain each directional extreme, and return `{ start, end, amplitude }` for every directional run. It must not inspect language or application state.

- [ ] **Step 3: Route by selected UI language**

Implement:

```javascript
function scribbleGestureProfile(points, language = currentLanguage()) {
  if (LATIN_SCRATCH_LANGUAGES.has(language)) return latinScratchGestureProfile(points);
  return { ...mixedScribbleGestureProfile(points), mode: 'mixed-scribble' };
}
```

Pass `currentLanguage()` explicitly from `maybeScribbleErase` and export `scribbleGestureProfile` and `latinScratchGestureProfile` through `window.__inkforge` for diagnostics. Do not add a mixed-profile fallback for `en` or `pt`.

- [ ] **Step 4: Run the web regression and verify GREEN**

Run the Task 1 command with output `.omx/logs/test-web-3.3.27-green.json`.

Expected: exit code 0, `language_aware_scribble_erase.passed` true, existing `local_scribble_erase`, `local_scribble_touch_only`, and `scribble_letter_r_guard` pass, with no console errors or dialogs.

- [ ] **Step 5: Review the focused diff**

Run:

```powershell
git diff --check
git diff -- web/app.js tools/test_web.py
```

Expected: no whitespace errors; no changes to object hit radius, deletion, undo, persistence, or shape recognition.

### Task 3: Prepare version 3.3.27 and Android assets

**Files:**
- Modify: `web/app.js`
- Modify: `web/native-bridge.js`
- Modify: `web/upgrade32.js`
- Modify: `web/index.html`
- Modify: `android/app/build.gradle`
- Modify: `android/app/src/main/java/com/inkforge/notesstudio/MainActivity.java`
- Modify: `README.md`
- Modify: `docs/README-KO.md`
- Modify: `docs/INSTALL-KO.txt`
- Create: `docs/CHANGELOG-3.3.27-KO.md`
- Replace: `android/app/src/main/assets/public/`

- [ ] **Step 1: Bump every runtime version**

Replace `3.3.26` with `3.3.27` in runtime metadata and set Android `versionCode 359`. Update the README and installation filenames to:

```text
bad-note-Android-3.3.27-Update.apk
bad-note-Android-3.3.27-SideBySide.apk
```

- [ ] **Step 2: Add multilingual release notes**

At the start of each `RELEASE_NOTES` language array, describe that English and Portuguese now require deliberate left-right scratching and that cursive/looped writing is less likely to erase content. Keep Korean, English, Japanese, Chinese, and Portuguese text meaningful rather than copying untranslated Korean.

- [ ] **Step 3: Record the Korean changelog**

Create `docs/CHANGELOG-3.3.27-KO.md` with behavior changes, TDD evidence, APK metadata, and the physical-device testing limitation.

- [ ] **Step 4: Synchronize web assets into Android**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\copy_script.ps1
```

Expected: `Synced web assets to ...\android\app\src\main\assets\public`.

- [ ] **Step 5: Confirm source and packaged assets match**

Run:

```powershell
git diff --no-index -- web\app.js android\app\src\main\assets\public\app.js
git diff --no-index -- web\native-bridge.js android\app\src\main\assets\public\native-bridge.js
```

Expected: both commands report no differences.

### Task 4: Verify and build the release

**Files:**
- Generated, ignored: `bad-note-Android-3.3.27-Update.apk`
- Generated, ignored: `bad-note-Android-3.3.27-SideBySide.apk`

- [ ] **Step 1: Run fresh full web verification**

Run the Task 2 GREEN command again after asset/version changes. Expected: exit code 0 and version fields `3.3.27`.

- [ ] **Step 2: Build both release variants**

Run:

```powershell
python tools\build_apk.py --variant both --gradle "C:\Users\js100\Desktop\coding\BADNOTE\.build-tools\gradle-8.14.3\bin\gradle.bat"
```

Expected: both release builds complete with exit code 0.

- [ ] **Step 3: Copy named release artifacts**

Copy the update and side-by-side Gradle outputs to the repository root using the 3.3.27 filenames. APK files remain ignored and must not be committed.

- [ ] **Step 4: Verify both APKs**

Run `tools/verify_apk.py` for both files with build tools `.build-tools/android-sdk/build-tools/36.1.0`.

Expected for each APK: `version_name 3.3.27`, `version_code 359`, correct application ID, ZIP CRC valid, 16 KB alignment true, and signing verification true.

- [ ] **Step 5: Inspect final source state**

Run `git diff --check`, inspect `git diff --stat`, and confirm `.codex-remote-attachments/` remains untracked and unstaged.

### Task 5: Commit, push, and publish GitHub Release

**Files:**
- Stage only tracked source, tests, documentation, and synchronized Android assets.

- [ ] **Step 1: Create the implementation commit**

Use a Lore commit describing why Latin cursive needs a stricter profile, rejected global replacement/OCR approaches, exact test and APK evidence, and physical S Pen testing as the only untested item.

- [ ] **Step 2: Rebase if remote main advanced**

Fetch `origin`, inspect remote changes, and rebase without discarding user work. Re-run focused verification if a conflict changes affected files.

- [ ] **Step 3: Push main and tag**

Push `main`, create annotated tag `v3.3.27` at the pushed commit, and push the tag.

- [ ] **Step 4: Create GitHub Release**

Create a non-draft, non-prerelease release for `v3.3.27` using the Korean changelog summary and upload both verified APK files. Do not commit credentials or print tokens.

- [ ] **Step 5: Verify remote release state**

Confirm the release target equals local `HEAD`, both APK asset names are present, and `origin/main` equals local `main`.
