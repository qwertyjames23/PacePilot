# P0-1 — Responsive Coaching Pace (Implementation Plan)

> **Traces to:** [`docs/design/audio-first-experience.md`](../design/audio-first-experience.md) · [`docs/plans/p0-audio-first-roadmap.md`](./p0-audio-first-roadmap.md) (item **P0-1**)
> **Core promise served:** *Run by ear, not by screen.*
> **Status:** Planning only — no production code. Detailed enough to implement in a separate session.
> **Last updated:** 2026-06-23

---

## 1. Problem statement

The pace used for live coaching is the **cumulative average of the current kilometre**, and it does not exist at all until 120 m into a run.

- `PaceCalculator.currentLapPaceSecPerKm()` returns a value only once `currentKmDistanceMeters >= MIN_DISTANCE_FOR_CURRENT_KM_PACE_METERS` (**120 m**); before that it returns the last completed km's pace, or `NaN`.
- Because it averages the *whole current km so far*, it reacts slowly: after 600 m of running slow, speeding up barely moves a 600 m average, so the coach keeps saying "too slow" long after the runner has corrected.

For an audio coach this is fatal in two ways:
1. **Dead start.** No pace (and therefore no cues, and a "Waiting for GPS…" status) for the first ~120 m / ~50 s — exactly when the runner most needs to settle into pace without looking.
2. **Laggy/misleading correction.** Cues reflect where the runner *was*, not where they *are*, causing both stale nagging and surge-overshoot.

Every other P0 item (correction loop, audio cues, haptics) depends on a pace number that is **timely and trustworthy**. P0-1 produces that number.

---

## 2. Success criteria

1. A **coaching pace** value is available within **≤ 30 m of accumulated movement** (≈10–15 s at jogging speed), instead of 120 m.
2. The coaching pace **reflects recent effort** — a sustained change in speed is reflected within roughly the rolling-window duration (target ≈ 20 s), not diluted by the whole-km average.
3. Live **cue feedback and the on-screen hero pace** are driven by the coaching pace.
4. **Splits, average pace, and run history are unchanged** (they continue to use the existing cumulative/per-km pace).
5. The new windowing logic is covered by **JVM unit tests** that run without an emulator.
6. `gradlew assembleDebug` is green; existing behavior for completed runs is preserved.

---

## 3. Current behavior

- `PaceCalculator.onLocation(location)` computes per-segment distance/time, applies accuracy/speed sanity filters, accumulates accepted distance into `totalDistanceMeters` and `currentKmDistanceMeters`/`currentKmDurationSec`, maintains an EWMA `recentSpeedMps`, splits at km boundaries, and returns a `PaceSnapshot`.
- `PaceSnapshot.paceSecPerKm` = `currentLapPaceSecPerKm()` = current-km cumulative average (after 120 m) → else last completed km → else `NaN`.
- `LocationService.callback.onLocationResult` feeds `latest.paceSecPerKm` into:
  - `handlePaceFeedback(latest.paceSecPerKm, activeTarget)` (voice/haptic too-fast/slow),
  - `handleKilometerVoice(latest.completedKilometers, latest.paceSecPerKm)` (km announcement),
  - `publishMetrics(...)` → broadcast `EXTRA_PACE_SEC_PER_KM`.
- `MainActivity.metricsReceiver` reads `EXTRA_PACE_SEC_PER_KM` → `heroPaceValue`, and shows **"Waiting for GPS…"** whenever `pace.isNaN()`; the On Target / Too Slow / Too Fast classification also uses this pace.

Net: live display, status, and cues all ride the sluggish, late lap pace.

---

## 4. Desired behavior

Introduce a **second pace, computed in parallel**, with a clear division of responsibility:

| Pace | Definition | Used by |
|---|---|---|
| **Coaching pace** *(new)* | Rolling pace over the last ~20 s of accepted movement; appears after ~30 m | Live cue feedback, hero pace display, live status, live notification pace, (future) status-on-demand |
| **Lap / split pace** *(existing, unchanged)* | Cumulative current-km average / completed-km pace | Km-split announcement, run history splits, average pace |

So: coaching reacts fast and starts early; splits/averages stay precise. The runner hears/feels guidance within seconds of starting, and the on-screen "current pace" is the same number the coach is reacting to.

---

## 5. User impact

- **Coaching starts within seconds**, not after the first ~120 m of silence — the start of every run is coached.
- **Corrections feel current** — "ease off" / "push" reflect the last ~20 s, so the runner can trust them without glancing to confirm.
- **No regression** to post-run data: splits, averages, and history read exactly as before.
- Directly advances *Run by ear, not by screen*: a trustworthy, timely live pace is the precondition for every audio/haptic cue in P0-6 and beyond.

---

## 6. Technical approach — options

### Option A — Derive coaching pace from the existing smoothed speed (`recentSpeedMps`)
Compute `coachingPace = 1000 / recentSpeedMps` (sec/km) when speed is above a small threshold.
- **Pros:** Minimal new state; immediate; reuses existing EWMA.
- **Cons:** EWMA is weighted *per GPS sample*, not per time/distance, so responsiveness drifts with GPS cadence; the aggressive `SPEED_DECAY_WHEN_STOPPED = 0.35` makes pace spike oddly when momentarily stopped; harder to reason about and to unit-test deterministically.

### Option B — Time-windowed rolling pace (recommended core)
Maintain a small queue of **accepted segments** `(meters, seconds, endTimeMs)`; keep only those within the last `COACHING_WINDOW_MS`; coaching pace = `Σmeters / Σseconds` (converted to sec/km), gated by a minimum retained distance.
- **Pros:** Responsiveness is defined in real time and independent of sample cadence; easy to reason about and to unit-test; naturally ages out when the runner stops.
- **Cons:** A little more state; needs a min-distance gate to avoid noisy early values.

### Option C — Distance-windowed rolling pace (last N metres)
Same as B but windowed by distance (e.g., last 200 m).
- **Pros:** Stable across speeds.
- **Cons:** Less "real-time" when slow (200 m takes much longer at slow pace, so reaction lags exactly when you most want feedback). Worse fit for a *responsive* coach.

### Option D — Hybrid (recommended refinement of B)
Option B with a **validity gate**: emit a value only when retained distance ≥ `COACHING_MIN_DISTANCE_METERS` **and** retained time > 0; otherwise `NaN`. Trimming/aging is advanced on **every** location update (even rejected ones) using the incoming GPS timestamp, so a stop drains the window to `NaN`.

---

## 7. Recommended approach

**Option D** — a time-windowed rolling pace with a min-distance validity gate, encapsulated in a **new pure-Kotlin class `RollingPaceWindow`** (no Android imports), owned by `PaceCalculator` and surfaced as a new `PaceSnapshot.coachingPaceSecPerKm`. Wire the coaching pace into cue feedback, the hero display, the live status, and the live notification. Leave lap/split/average logic untouched.

**Why this one:** it isolates all new logic in a dependency-free class that is trivially unit-testable on the JVM (also chipping away at the R7 testing gap), gives deterministic, cadence-independent responsiveness, and keeps the change additive and easy to roll back.

### Parameters (initial, tunable — document as constants)
- `COACHING_WINDOW_MS = 20_000L` — rolling window length (~20 s).
- `COACHING_MIN_DISTANCE_METERS = 30.0` — minimum retained distance before emitting a value.
- Computation: `paceSecPerKm = (Σseconds) / (Σmeters / 1000.0)`; return `NaN` if `Σmeters < COACHING_MIN_DISTANCE_METERS` or `Σseconds <= 0`.
- Aging: on every query, drop segments whose `endTimeMs < nowMs - COACHING_WINDOW_MS`, where `nowMs` is the latest GPS sample time (guard against time going backwards by tracking the max seen timestamp).

### Pace semantics decision
Use **Σmeters / Σseconds of retained moving segments** (running pace), *not* `meters / wall-clock-span`. Rationale: brief 1–2 s GPS gaps shouldn't inflate pace; genuine stops are handled by manual pause now and **auto-pause (P1-6)** later. Aging still drains the window to `NaN` if movement stops for longer than the window — an acceptable interim until auto-pause lands.

---

## 8. Exact files / classes / functions affected

### New
- **`app/src/main/java/com/example/runningpace/RollingPaceWindow.kt`** *(new file, pure Kotlin)*
  - `class RollingPaceWindow(windowMs: Long, minDistanceMeters: Double)`
  - `fun add(meters: Double, seconds: Double, endTimeMs: Long)` — append an accepted segment.
  - `fun paceSecPerKm(nowMs: Long): Double` — trim to window, return windowed pace or `NaN`.
  - `fun reset()` — clear all state (and any max-timestamp guard).
  - Internal: a deque/ArrayDeque of segment records + a `maxSeenTimeMs` guard.

- **`app/src/test/java/com/example/runningpace/RollingPaceWindowTest.kt`** *(new test file — establishes the `src/test` JVM source set; `junit` dep already declared in `app/build.gradle.kts`)*

### Modified
- **`PaceCalculator.kt`**
  - `data class PaceSnapshot` — add field `val coachingPaceSecPerKm: Double = Double.NaN`.
  - `class PaceCalculator` — add `private val coachingWindow = RollingPaceWindow(COACHING_WINDOW_MS, COACHING_MIN_DISTANCE_METERS)`.
  - `fun onLocation(location)` — when a segment is **accepted** (inside the `shouldCountSegment` branch), call `coachingWindow.add(segmentMeters, deltaSec, location.time)`; then compute `coachingWindow.paceSecPerKm(location.time)` and include it in the returned `PaceSnapshot`. (Aging happens inside `paceSecPerKm`, so it runs on every update including rejected ones.)
  - `fun reset()` — add `coachingWindow.reset()`.
  - *(Optional, recommended)* add `fun resetCoachingWindow()` delegating to `coachingWindow.reset()` for use on resume.
  - `companion object` — add `COACHING_WINDOW_MS`, `COACHING_MIN_DISTANCE_METERS`.

- **`LocationService.kt`**
  - `callback.onLocationResult` — change the feedback call to use the coaching pace: `handlePaceFeedback(latest.coachingPaceSecPerKm, activeTarget)`. **Leave `handleKilometerVoice(...)` using `latest.paceSecPerKm`** (the completed-km pace is correct for the split announcement).
  - `publishMetrics(...)` — add `putExtra(EXTRA_COACHING_PACE_SEC_PER_KM, snapshot.coachingPaceSecPerKm)`. Keep `EXTRA_PACE_SEC_PER_KM` as-is.
  - `resumeTracking()` — *(optional)* call `calculator.resetCoachingWindow()` so a pre-pause pace can't bleed in after a short pause.
  - `buildNotification(snapshot)` — *(recommended)* use coaching pace for the live "Pace" text so the notification matches what the coach is reacting to. (Low priority; can defer.)
  - `companion object` — add `const val EXTRA_COACHING_PACE_SEC_PER_KM = "coaching_pace_sec_per_km"`.

- **`MainActivity.kt`**
  - `metricsReceiver.onReceive` — read `val coachingPace = intent.getDoubleExtra(LocationService.EXTRA_COACHING_PACE_SEC_PER_KM, Double.NaN)`; set `heroPaceValue.text = formatPaceValue(coachingPace)`; use `coachingPace` (not the lap pace) in the On Target / Too Slow / Too Fast classification; change the `pace.isNaN()` status copy from **"Waiting for GPS…"** to **"Warming up…"** (more honest — a fix may already exist; the runner just hasn't moved 30 m yet).
  - The hidden/legacy `paceText` and `EXTRA_PACE_SEC_PER_KM` usage in the activity can remain untouched (out of scope; `paceText` is already not displayed).

### Out of scope for P0-1 (note for traceability)
- The full **GPS-state status machine** ("Acquiring GPS" vs "Ready — start moving" vs a real "no fix" state) requires broadcasting a fix-received signal and belongs to the status/honesty work (relates to G5 / P0-8). P0-1 only changes the NaN copy to a neutral "Warming up…".
- Cue **cadence/hysteresis** smoothing is **P0-6**; P0-1 must produce a pace that is *reasonable*, not perfectly smooth — P0-6 damps cue frequency.

---

## 9. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Coaching pace is noisier than the old lap average → jittery cues | Med | 20 s window already averages ~10–20 samples; only **accepted** (accuracy-filtered) segments enter; P0-6 hysteresis/cadence governor will further damp cue firing. |
| Window tuning wrong (too twitchy or too sluggish) | Med | Constants are centralized and documented; validate on a real run; adjust `COACHING_WINDOW_MS` / `COACHING_MIN_DISTANCE_METERS`. |
| GPS timestamp anomalies (backwards/duplicate `location.time`) | Low | Track `maxSeenTimeMs`; never let "now" go backwards; segments with non-positive `seconds` are ignored. |
| Stale pace bleeds across a short pause | Low | Aging drains the window after `COACHING_WINDOW_MS`; optional `resetCoachingWindow()` on resume removes it entirely. |
| Behavior-change confusion (hero now differs from per-km splits) | Low | Hero = "current pace" (coaching), splits = per-km average — semantically distinct and labeled. |
| Unintended regression to splits/average/history | Low | Lap/split path is untouched; covered by manual regression checklist. |

---

## 10. Edge cases (must all be handled or consciously deferred)

1. **Fix acquired, runner standing still** → no accepted segments → window below min distance → `coachingPace = NaN` → status "Warming up…" (not "Waiting for GPS…"), no false cues.
2. **Slow warmup walk** → 30 m takes longer but still far earlier than 120 m; pace appears, no premature nagging (warmup grace is P1-3).
3. **GPS jitter while stationary** → sub-accuracy segments already rejected by `PaceCalculator`; nothing enters the window → no phantom pace.
4. **Sudden GPS jump** → segment capped by `MAX_SEGMENT_DISTANCE_METERS` (60 m) / `MAX_REASONABLE_SPEED_MPS` (8.5) and rejected → window unaffected.
5. **Manual pause > window length** → aging drains window → `NaN` on resume until 30 m again (acceptable). **Short pause** → optional `resetCoachingWindow()` prevents stale carry-over.
6. **Kilometre boundary** → coaching window does **not** reset (continuous), only splits reset — desired.
7. **Stop/restart run** → `reset()` clears the window with all other state.
8. **Zero / near-zero retained time** → guarded → `NaN`.
9. **Treadmill / no GPS** → no segments → `NaN` (expected; treadmill mode is P2-2).
10. **Very fast pace change (sprint then walk)** → reflected within ~window seconds; this is the intended responsiveness, not a bug.

---

## 11. Test strategy

### Unit tests (JVM, no emulator) — `RollingPaceWindowTest`
Target the pure `RollingPaceWindow`. Concrete, deterministic cases:

1. **Below min distance → NaN.** Add segments totalling < 30 m → `paceSecPerKm` returns `NaN`.
2. **Steady pace correctness.** Add 100 m over 30 s (e.g., several segments) with `endTimeMs` within window → expect **300 s/km** (5:00/km) within a small epsilon.
3. **Reacts to a change.** Fill window at 360 s/km (6:00), then add newer faster segments and advance `nowMs` so older slow segments age out → pace trends toward the faster value.
4. **Window trimming.** Add an old segment, then query with `nowMs` beyond `windowMs` later → old segment excluded.
5. **Stop drains to NaN.** After segments, query with `nowMs` advanced past `windowMs` with no new adds → `NaN`.
6. **Timestamp-backwards guard.** Feed a smaller `endTimeMs` after a larger one → no crash, no negative span; result remains sane.
7. **reset() clears.** After `reset()`, returns `NaN` until refilled.

### Integration (deferred / manual)
Full `PaceCalculator.onLocation` involves `Location.distanceTo` (Android framework), so JVM-only integration is not pursued here; it's covered by the manual checklist below and by future Robolectric tests under **R7**. (P0-1 deliberately isolates the new, testable logic in `RollingPaceWindow`.)

### Build verification
`gradlew assembleDebug` green; `gradlew testDebugUnitTest` (or `test`) green for the new tests.

---

## 12. Manual test checklist (on a real device, outdoors)

- [ ] Start a run standing still → status shows **"Warming up…"** (not "Waiting for GPS…"), no cues, no phantom pace.
- [ ] Begin moving → **coaching/hero pace appears within ~30 m / ~10–15 s** (well before the old 120 m).
- [ ] Run steady → hero pace is stable and plausible; cues behave (no constant nagging while on pace).
- [ ] Deliberately slow down for ~20–30 s → hero pace **rises (slower)** within ~window seconds and a "too slow" cue fires.
- [ ] Speed back up → hero pace **drops (faster)** within ~window seconds and nagging **stops** promptly (no lingering "too slow").
- [ ] Cross a km boundary → no glitch/reset in hero pace; the **km split announcement** still uses the completed-km pace and sounds correct.
- [ ] Pause, wait > 25 s, resume → no stale pre-pause pace; pace re-appears after moving ~30 m.
- [ ] Pause briefly (< 10 s), resume → pace behaves sanely (and, if `resetCoachingWindow()` added, restarts cleanly).
- [ ] Stop the run → **summary, splits, average pace, and history match expectations** (regression — should be unchanged vs. before this change).
- [ ] Compare hero "current pace" vs the per-km split list → they differ as expected (current vs per-km average), no confusion.

---

## 13. Acceptance criteria

1. With GPS available, after the runner begins moving, `coachingPaceSecPerKm` becomes non-`NaN` **within 30 m of accepted distance** (verified by manual test; logic verified by unit test #1/#2).
2. A sustained pace change is reflected in the coaching pace **within ≈ `COACHING_WINDOW_MS`** (verified by unit test #3 and manual slow/speed-up steps).
3. Live cue feedback (`handlePaceFeedback`) and the hero pace display are driven by the coaching pace; the NaN status reads **"Warming up…"**.
4. Km-split announcements, average pace, run history, and the splits view are **byte-for-byte equivalent** in behavior to pre-change (no use of coaching pace in those paths).
5. New `RollingPaceWindow` unit tests pass; `gradlew assembleDebug` and the unit-test task are green.
6. No new `NaN`/divide-by-zero crashes across the edge cases in §10.

---

## 14. Rollback plan

- **Granularity:** implement as a **single, self-contained commit** (additive: new file + new field + new extra + consumer rewiring). Reverting that one commit fully restores prior behavior.
- **Soft switch (recommended):** gate the *consumer* choice behind a single constant, e.g. `LocationService.USE_COACHING_PACE = true`. When `false`, `handlePaceFeedback`, the broadcast, and the hero/status read the original `paceSecPerKm`. This allows disabling the new behavior without removing code, and supports quick A/B on-device comparison during tuning.
- **Data safety:** no persisted-data format changes (no `RunRecord`/SharedPreferences schema change), so rollback has **zero migration risk**; existing saved runs are unaffected.
- **Blast radius:** the lap/split/average/history pipeline is untouched, so even if coaching pace is disabled or reverted, run recording remains correct.

---

## 15. Suggested implementation order (for the build session)

1. Create `RollingPaceWindow` + its unit tests (TDD: write tests from §11 first, then the class).
2. Add `coachingPaceSecPerKm` to `PaceSnapshot`; wire the window into `PaceCalculator.onLocation`/`reset`.
3. Add the broadcast extra + route coaching pace into `handlePaceFeedback` and `publishMetrics` (behind `USE_COACHING_PACE`).
4. Update `MainActivity.metricsReceiver` (hero pace, status classification, "Warming up…" copy).
5. `gradlew testDebugUnitTest` + `gradlew assembleDebug`; then run the §12 manual checklist and tune the two constants.
