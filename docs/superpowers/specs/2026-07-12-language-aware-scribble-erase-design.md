# Language-aware scribble erase design

## Goal

Reduce accidental scribble erasing while writing English or Portuguese cursive, while making an intentional short left-right scratching gesture erase reliably. Korean, Japanese, and Chinese keep the existing mixed circular and dense-scribble behavior.

## Scope

- Use the selected application language in `state.settings.language` as the gesture profile selector.
- Apply the Latin scratch profile only to `en` and `pt`.
- Keep the existing gesture profile unchanged for `ko`, `ja`, and `zh`.
- Keep hit testing unchanged so only objects touched by the gesture path are removed.
- Release the change as Android version `3.3.27` with `versionCode 359`.

OCR language and document contents do not select the profile. Changing the UI language takes effect for the next completed pen stroke and does not rewrite saved notes.

## Approaches considered

### Selected: separate language profiles

Route `en` and `pt` to a horizontal scratch classifier and all other supported languages to the current classifier. This is the smallest change, preserves established behavior for non-Latin languages, and makes the Latin behavior independently testable.

### Rejected: replace the classifier for every language

Using horizontal scratching globally would simplify the implementation but would remove circular rubbing behavior already used by Korean, Japanese, and Chinese users.

### Rejected: combine OCR with eraser classification

Recognizing the stroke as text before deciding whether it is an eraser gesture could protect more glyphs, but it adds model latency and model-availability failure modes to a gesture that must respond immediately and offline.

## Architecture

`scribbleGestureProfile(points, language)` remains the single classification entry point. It selects one of two pure classifiers:

- `latinScratchGestureProfile(points)` for `en` and `pt`.
- The existing dense scribble classifier for `ko`, `ja`, and `zh`.

`maybeScribbleErase()` passes `currentLanguage()` to the classifier. The classifier returns the existing `dense` and `local` fields, plus a `mode` field for diagnostics and tests. Erasing, undo checkpoints, selection clearing, persistence, and object hit testing continue through the existing path.

## Latin scratch classifier

The classifier works in screen coordinates so zoom does not change the gesture threshold. It ignores tiny X-axis jitter before counting direction changes.

An English or Portuguese stroke is an intentional scratch only when all conditions hold:

1. At least 10 sampled points and a screen-space width of at least 24 px.
2. Width is at least 1.15 times height, with a minimum height of 4 px so a simple horizontal line is excluded.
3. Horizontal travel is at least 3 times vertical travel. The initial 1.35 ratio was rejected during RED/GREEN evaluation because a repeated narrow ellipse still produced about 2.8 times as much horizontal as vertical travel.
4. The filtered X direction reverses at least three times.
5. At least three alternating traversals move by `max(9 px, 22% of gesture width)`.
6. At least three traversals cross the gesture's horizontal midpoint; at least two span 55% or more of the gesture width.
7. Total path length divided by the bounding-box diagonal is at least 2.45.

Self-intersection, closure, and circularity are deliberately not required. The stronger full-width alternating traversal conditions make the Latin profile slightly less sensitive than the existing classifier and reject cursive glyphs that contain loops but progress mainly left-to-right.

## Existing-language behavior

The current R-shaped glyph guard and mixed dense-scribble conditions remain in place for `ko`, `ja`, and `zh`. They are not used as a fallback for `en` or `pt`; otherwise a looped Latin glyph could still enter the old circular path and erase content.

Unknown or invalid language values follow the application's existing `currentLanguage()` fallback to English and therefore use the Latin scratch profile.

## Hit testing and safety

The classifier only decides whether a completed pen stroke is an erase gesture. `objectIntersectsScribble()` remains responsible for deletion and continues to use path-to-path contact rather than the whole gesture bounding box. This preserves the requirement that nearby untouched objects remain.

If the scratch is valid but touches no object, it is committed as normal ink because `maybeScribbleErase()` returns false. No destructive action occurs without an intersecting object and an undo checkpoint.

## Tests

Add browser regression cases before production changes and confirm they fail for the missing language-specific behavior:

- English horizontal scratch erases the touched stroke.
- Portuguese horizontal scratch erases the touched stroke.
- English looped/circular rubbing does not erase and is committed as ink.
- Portuguese cursive-like `r`/`m` progression does not erase.
- English scratch removes the touched target while preserving a nearby untouched target.
- Korean retains the existing local mixed scribble behavior.

The full web regression suite must pass after implementation. Android source assets must be synchronized, both APK variants built, and each APK checked for version metadata, ZIP integrity, 16 KB alignment, and signing verification.

## Release

Update web, Android bridge, Android application, README, installation guide, and multilingual in-app release notes to `3.3.27`. Publish update and side-by-side APKs under GitHub tag `v3.3.27`, with release notes explaining the English and Portuguese gesture change.

## Risks

- Device pointer sampling varies. Relative traversal thresholds reduce this risk, while the minimum point count still requires hardware validation on a physical S Pen device.
- Very narrow intentional scratches may be rejected. This is intentional because the requested profile is less sensitive for cursive-writing languages.
- A user who writes in English while the application language remains Korean retains the Korean classifier by design.
