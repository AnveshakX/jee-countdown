# Countdown Wallpaper: P0 audit and report

Status date: 21 Nov 2026. Read section 0 first: it says exactly what was and was not verified.

## 0. Verification status (honest summary)

The environment used for this pass had a JDK 21 and Python, but **no Kotlin compiler, no Gradle, no Android SDK and no network**.
So:

| Check | Result |
|---|---|
| Kotlin compiles, Gradle builds, APK assembles | **NOT run.** First real check is the GitHub Actions run. |
| The 60 Kotlin unit tests execute | **NOT run.** Same reason. |
| Pure logic (engine, Cadence, selection, codec, repo, model builder, config clamping) | **Verified by a Java port** (`docs/VerifyP0.java`, run with `java docs/VerifyP0.java`): every assertion from the Kotlin tests plus the new Cadence ones, **151 checks, 0 failures**, on JDK 21 `java.time`. This proves the logic and the expected numbers, not that the Kotlin source compiles. |
| DST / time-zone expected values | **Cross-checked independently in Python zoneinfo** (8 cases incl. NY spring/fall, Havana midnight gap, LA, IST): all agree. |
| Manifest/resources | All XML well-formed; every `@xml/@layout/@style/@mipmap/@drawable/@color` reference and every manifest class resolves; Kotlin files have balanced brackets. |
| Lifecycle, battery, rendering on a device | **NOT tested on a device.** Findings below are from code inspection. |
| Lint | Configured to report in CI (not blocking yet). No baseline exists. |

Provenance: the working tree already contained a partial refactor from an earlier, interrupted pass (new engine, model, codec,
tests; empty `docs/`). I inspected all of it rather than assuming it was correct, then fixed what I found. I cannot see what
tooling that pass used, so I treat everything in it as unexecuted.

## 1. Architecture (current)

- `CountdownEngine.kt`: pure date maths (`java.time`). Single source of truth for calendar days, exact time until the event
  date starts, and exact time left today. Also `Cadence` (aligned wake-ups, next midnight).
- `ExamModel.kt`: countdown entity, v2 string codec (+ legacy migration), event selection, progress, `ExamRepo` over a
  `KeyValueStore` (JVM-testable). `Exams.kt` is the thin Android facade.
- `CfgModel.kt` / `CfgStore.kt`: settings, pure `CfgCodec` (clamps/validates), in-memory cache with debounced persistence.
- `Model.kt`: `ModelBuilder` produces everything a surface shows (number, label, date, time-left text, progress, urgency)
  from `(events, settings, now)`. Wallpaper, widget, preview, Desk mode and screensaver all use it, so they cannot disagree.
- `Renderer.kt`: drawing only. Cached background layer, per-second frame cache, no per-frame allocation.
- Surfaces: `CountdownWallpaperService`, `CountdownWidget`, `CountdownView` (preview, Desk mode, screensaver), `MainActivity`.

Event model (a deliberate, documented assumption): an event is an all-day **calendar date** in the device's *current* time
zone, starting 00:00 local. No start time or exam time zone is assumed. Both are P1 candidates.

## 2. Findings

| ID | Sev | Finding | Status |
|---|---|---|---|
| H1 | **Critical (release)** | `targetSdk 34`. Google Play requires **API 36** for new apps and updates since 31 Aug 2026; a new app cannot be submitted. | Fixed in config: `compileSdk/targetSdk 36`, AGP **8.9.1** (minimum for API 36), Gradle **8.11.1**. **Unverified build.** See section 5. |
| H2 | High | Widget day count stayed stale after midnight: the manifest registered `DATE_CHANGED`, which is *not* exempt from the API 26+ implicit-broadcast limits (only `TIME_SET` and `TIMEZONE_CHANGED` are), so the 30-minute system refresh was the only trigger. | Fixed: inexact `AlarmManager` alarm just after each local midnight (no permission, no forced wake-up), re-armed on every update, cancelled when the last widget is removed. Dead `DATE_CHANGED` filter removed. |
| H3 | High | Countdown arithmetic was spread across surfaces and used wall-clock maths (wrong by an hour across DST for the "hours" unit). | Fixed in tree: one engine; exact `Duration` for hours/time-left; DST tested (23h/25h days, gap midnight). |
| H4 | High | Per-frame cost while animating: events re-parsed and re-sorted every frame, strings/objects allocated per frame, new `Paint`s in the widget path, background rebuilt on every slider tick. | Fixed in tree: cached event list with a version counter, per-second model cache, reused paints/rects, background reuses its bitmap. |
| M1 | Medium | Fixed 30 s / 1 s sleeps: minute and midnight changes up to 30 s late, seconds readout skipped or repeated. | Fixed: `Cadence.alignedDelay` aligns every idle/seconds/Desk tick to the clock grid (lands ~20 ms after the boundary). |
| M2 | Medium | Cloud backup included event names, labels and settings. Brief says personal data stays local. | Fixed: excluded from cloud backup (Android 11- and 12+ rules); direct phone-to-phone transfer still carries it. **Consequence:** a cloud restore on a new phone starts empty. A local export/import is a P1 item. |
| M3 | Medium | Persistence: unparseable stored values could crash or reset everything. | Fixed in tree: codec skips only bad entries, logs a count, clamps out-of-range settings, migrates the legacy format once. |
| M4 | Medium | Prefs written on every keystroke/slider event. | Fixed in tree: 300 ms debounce, flushed in `onPause`. Residual risk: a kill within 300 ms of the last change can lose that one change. |
| M5 | Medium | Photo import ran on the main thread and wrote the live file in place. | Fixed in tree: background thread, temp file then rename, decode at suitable sizes. |
| M6 | Medium | **Open:** blur is recomputed from the full-size photo on the main thread on each blur-slider step. Not measured. | Open. Mitigation idea: blur from a pre-shrunk copy. |
| M7 | Medium | **Open:** widget refresh (including photo decode at <=720 px) runs on the main thread inside the broadcast. Small, but not free. | Open (`goAsync` + worker thread is the fix). |
| M8 | Medium | **Open:** each surface keeps its own full-size background bitmap (wallpaper + preview). Could be heavy on low-RAM phones. | Open; released on detach / trim-memory. |
| M9 | Medium | Risk: Kotlin 1.9.24 is used with Gradle 8.11.1, beyond the Gradle range JetBrains documents for it. It usually works with warnings. | If CI fails on this, bump Kotlin to 2.0.21 or 2.1.x (one line in root `build.gradle`). |
| L1 | Low | `PrefsStore` diagnostic used a possibly-unchecked smart cast. | Fixed (`v?.javaClass?.name`). |
| L2 | Low | Photo button said "Change photo" even if the file was missing (e.g. after restore). | Fixed: based on file existence. |
| L3 | Low | Open dialogs close on rotation. | Open. |
| L4 | Low | Widget bitmap uses the minimum widget size and is stretched slightly when the widget is larger. | Open. |
| L5 | Low | Lint is report-only; no instrumented (on-device) tests. | Open by design until a baseline exists. |

Other inspected areas with no defect found: no permissions requested; no notifications; no network use; wallpaper visibility,
surface create/destroy, screen on/off and duplicate-loop protection; Desk mode window flags are per-window so exit restores
the system; the screensaver releases bitmaps on detach; animation stops in battery saver and when system animations are off.

## 3. Files changed in this pass and why

- `CountdownEngine.kt`: added `Cadence` (aligned delays, next midnight).
- `Renderer.kt`: `nextDelayMs` uses `Cadence`.
- `CountdownWidget.kt`: rewritten refresh logic (midnight alarm, `onEnabled/onDisabled`, `updateAll` re-arms).
- `AndroidManifest.xml`: removed the dead `DATE_CHANGED` action.
- `backup_rules.xml`, `data_extraction_rules.xml`: local-first backup policy.
- `Storage.kt`, `MainActivity.kt`: L1, L2.
- `build.gradle` (root and app), `.github/workflows/build.yml`: API 36 toolchain; versionCode 4 / 2.2.
- `CadenceTest.kt` (new, 9 tests), `docs/` (this file, `VerifyP0.java`).

Files inspected and left unchanged: `Model.kt`, `ExamModel.kt`, `CfgModel.kt`, `CfgStore.kt`, `Exams.kt`, `MotionPolicy.kt`,
`PhotoCache.kt`, `CountdownView.kt`, `CountdownWallpaperService.kt`, `DeskActivity.kt`, `CountdownDream.kt`, existing tests.

## 4. Tests

60 JUnit tests (plain JVM, no Android runtime, no extra dependencies beyond `junit:junit:4.13.2`):

| Class | Tests | Covers |
|---|---|---|
| `CountdownEngineTest` | 15 | today/tomorrow/past, midnight edges, leap years, month/year ends, DST both directions, 23h/25h days, zone differences, clock changes, weeks, the three quantities stay distinct |
| `ModelBuilderTest` | 11 | units, labels, urgency thresholds, next-event choice, time-left text incl. DST days, ring progress, shared model |
| `ExamModelTest` | 16 | codec round-trip, sanitising, legacy migration, corruption, selection, ring start, repo persistence across a simulated restart, edit/delete, upgrade |
| `CfgCodecTest` | 9 | defaults, every setting persists, clamping, wrong types, alpha forcing, text cleanup, unknown keys, catalog integrity |
| `CadenceTest` | 9 | aligned delays (incl. negative time and a sweep), midnight in several zones, DST days, a midnight swallowed by a DST gap |

"Integration" coverage is done at the JVM level through an in-memory store (restart recovery, upgrade, settings restoration).
On-device widget/lifecycle tests are not included.

CI (`.github/workflows/build.yml`): unit tests, debug APK, release build (R8 + resource shrinking, unsigned), lint (report only),
and uploads of test reports, lint report and both APKs.

## 5. Known limitations and what to check first

1. **First CI run is the real verification.** If it fails, the most likely causes are, in order: a Kotlin compile error (never
   compiled here), Kotlin 1.9.24 vs Gradle 8.11.1 (M9), a missing API 36 platform on the runner, an R8 release-only issue.
2. Targeting 36 changes behaviour: edge-to-edge is enforced (the settings screen uses `fitsSystemWindows`, so check the insets on
   a gesture-nav phone), predictive back is on by default, and large screens ignore orientation locks (none are set).
3. The midnight widget refresh and post-reboot re-arming rely on documented platform behaviour; not tested on a device.
4. Release APK is unsigned. Play needs a signed AAB and Play App Signing: set up before upload.
5. `applicationId` is still `com.jeecountdown`. It cannot change after the first Play upload.

## 6. Recommended P1 order

1. Run CI, fix whatever it reports; install the debug build on one phone and one tablet and check the five surfaces.
2. Choose the final `applicationId`, create a signing key, build a signed AAB.
3. Local export/import of settings and countdowns (replaces the cloud backup you no longer get).
4. M6/M7/M8 performance items once measured on a real device.
5. Optional event start time and time zone; notifications for milestones.
