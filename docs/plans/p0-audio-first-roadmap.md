# PacePilot — Audio-First Release Roadmap

> **Status:** Release-oriented roadmap. Every item traces back to the product source of truth: [`docs/design/audio-first-experience.md`](../design/audio-first-experience.md).
>
> **The one test every item must pass:** does it help the runner **"Run by ear, not by screen"** — complete a run without looking at the phone?
>
> **Last updated:** 2026-06-23 · **No production code yet — this is planning only.**

---

## How to read this

Tiers map to releases, not just priority:

| Tier | Release | Goal |
|---|---|---|
| **P0** | Alpha (dogfood) | The eyes-free coaching loop *works* and is *trustworthy* |
| **P1** | Beta | The coach is genuinely *good* and *controllable by ear* |
| **P2** | 1.0 (public) | *Robust* across real-world runs; breadth of modes; shippable polish |
| **P3** | Post-1.0 | Frontier coaching depth + nice-to-haves |

Each item lists: **User value · Effort (S/M/L) · Risk · Dependencies · Why it supports the core promise.** IDs in parentheses (G1, R6, C1, A1, U1…) cross-reference the codebase assessment so each item is traceable to a concrete finding.

Effort: **S** ≈ hours · **M** ≈ a day or two · **L** ≈ multi-day / structural.

---

## P0 — Alpha: the eyes-free loop works and is trustworthy

Nothing else matters until a runner can start, be coached, and stop **by ear**, trusting the pace blindly. This is the minimum viable coach.

### P0-1 · Responsive coaching pace (rolling window) + early pace (C1)
- **User value:** Cues react to what you're doing *right now*; coaching starts within seconds instead of after the first 120 m of silence.
- **Effort:** M
- **Risk:** Med — window length needs field tuning (too short = jittery cues, too long = sluggish).
- **Dependencies:** none — this is the foundation.
- **Why it supports the promise:** Every spoken/felt cue is only as trustworthy as the live pace behind it. Without a responsive pace, the ear-coach either lies or stays silent — and the runner reaches for the screen.

### P0-2 · Fast GPS first fix (G1, G2, G3)
- **User value:** Pace appears almost immediately, even standing still at the start line. No dead first minute.
- **Effort:** S–M
- **Risk:** Med — `getCurrentLocation` behavior and Play Services versions vary by device; removing batching slightly raises power draw.
- **Dependencies:** none.
- **Why it supports the promise:** You can't run by ear if there's no pace to coach for the first minute. "GPS ready" has to actually be true, fast.

### P0-3 · Audio-focus ducking + voice-engine-ready queue (R6)
- **User value:** Cues lower your music so they're actually heard; the first cue is never dropped.
- **Effort:** S–M
- **Risk:** Low–Med — audio-focus edge cases across OEMs/headsets.
- **Dependencies:** none.
- **Why it supports the promise:** The entire interface is sound. If it can't be heard over the runner's own audio, there is no eyes-free product.

### P0-4 · Audio start sequence (A1)
- **User value:** "Acquiring GPS → GPS ready → 3-2-1 → Go," so you pocket the phone on sound/feel and *know* the run truly started.
- **Effort:** M
- **Risk:** Low.
- **Dependencies:** P0-2 (fix readiness), P0-3 (audio).
- **Why it supports the promise:** Lets the run *begin* without watching the screen — the first eyes-free moment.

### P0-5 · Audio confirmations + spoken end-of-run summary (A2)
- **User value:** Every state change you can't see is confirmed by ear: "Paused," "Resumed," and a full spoken summary at the end.
- **Effort:** S–M
- **Risk:** Low.
- **Dependencies:** P0-3.
- **Why it supports the promise:** Pausing/ending from a pocket must be confirmed, not taken on faith.

### P0-6 · Correction loop: deviation bands + hysteresis + cadence governor
- **User value:** Nudges only when needed, silence when on pace, no chatter or nagging.
- **Effort:** M–L
- **Risk:** Med — making cadence/hysteresis *feel* right requires real-run tuning.
- **Dependencies:** P0-1 (pace), P0-3 (audio), P0-7 (haptics).
- **Why it supports the promise:** This *is* coaching by ear and feel — the heartbeat of the whole product.

### P0-7 · Haptic vocabulary (H1 base)
- **User value:** Feel corrections (slow / fast / back-on-pace / km / ready / alert) without interrupting your audio.
- **Effort:** S–M
- **Risk:** Low–Med — patterns must stay distinguishable through clothing.
- **Dependencies:** none.
- **Why it supports the promise:** "Felt before spoken" keeps music intact and makes minor corrections non-intrusive — central to a coach you keep running with.

### P0-8 · Trust & reliability baseline (R1, R4 + accuracy honesty)
- **User value:** The coach never coaches on bad data; no phantom default runs on restart; no junk runs saved from mis-taps.
- **Effort:** S–M
- **Risk:** Low.
- **Dependencies:** P0-1.
- **Why it supports the promise:** A coach you can't see must be trusted blind — and blind trust dies on a single confident lie. Freeze pace / suppress cues when accuracy is poor.

### P0-9 · Permission-at-launch + warmup through handoff (G4)
- **User value:** Even the very first run after install starts fast, without a silent dead patch.
- **Effort:** M
- **Risk:** Med — permission-flow ordering UX.
- **Dependencies:** P0-2.
- **Why it supports the promise:** The first run is when trust is won or lost; warmup makes "it just knows my pace" true from the start.

---

## P1 — Beta: a genuinely good coach, controllable by ear

The loop works; now make it *good*, *honest on real terrain*, and *controllable without the screen*.

### P1-1 · Eyes-free controls + status-on-demand (A3)
- **User value:** Pause/resume/stop and "what's my pace?" without taking the phone out — via notification/lockscreen buttons or a paired BT remote.
- **Effort:** M
- **Risk:** Med — earbud/BT button capture is inconsistent across devices; mitigate with notification actions + a guaranteed in-app fallback. (Shake is **excluded** — running motion false-fires it.)
- **Dependencies:** P0-3, P0-5.
- **Why it supports the promise:** Directly replaces the #1 reason runners pull the phone out — checking and controlling.

### P1-2 · Listening profiles: Coach / Minimal / Silent (LP)
- **User value:** Podcast/audiobook listeners aren't interrupted (Minimal = haptic-first); Deaf/HoH runners get haptics + screen (Silent).
- **Effort:** M
- **Risk:** Low–Med.
- **Dependencies:** P0-6, P0-7.
- **Why it supports the promise:** Keeps "by ear" usable for *every* runner alongside the audio they already listen to.

### P1-3 · Effort-aware coaching: terrain tolerance + warmup/cooldown grace
- **User value:** No more wrong "speed up!" up every hill or through every warmup — the credibility killers.
- **Effort:** M
- **Risk:** Med — distinguishing terrain from slacking without HR; keep heuristics conservative.
- **Dependencies:** P0-1, P0-6.
- **Why it supports the promise:** A coach that nags wrongly gets ignored — and an ignored coach sends the runner back to the screen.

### P1-4 · Smarter cues: magnitude + gradual phrasing + positive reinforcement (A5)
- **User value:** Actionable, encouraging guidance that prevents surge-and-overshoot yo-yo pacing.
- **Effort:** S
- **Risk:** Low.
- **Dependencies:** P0-6.
- **Why it supports the promise:** Higher-quality ear-coaching means less temptation to glance for confirmation.

### P1-5 · Channel-loss honesty: spoken GPS-trust alerts + headphone-disconnect + audio-focus loss (A4)
- **User value:** You always know if the coach can't see (weak GPS) or if audio dropped (earbuds out, incoming call).
- **Effort:** M
- **Risk:** Med — reliable headset/BT state detection.
- **Dependencies:** P0-3, P0-8.
- **Why it supports the promise:** Silent channel failure is the single worst failure for an app whose promise is *not looking* — the runner must never be coached into a dead channel.

### P1-6 · Auto-pause + inactivity auto-stop (with audio)
- **User value:** No nagging at stoplights; no dead-battery 2-hour junk runs after you forget to stop.
- **Effort:** M
- **Risk:** Med — false-positive/negative tuning (slow uphill grind vs. truly stopped).
- **Dependencies:** P0-1, P0-5.
- **Why it supports the promise:** Keeps the hands/eyes-free flow intact at the real-world stops every run has.

### P1-7 · Coaching settings (S1)
- **User value:** Tune band width/strictness, chattiness, vibration strength, and units (spoken + screen) to the run and carry position.
- **Effort:** M
- **Risk:** Low.
- **Dependencies:** P1-2.
- **Why it supports the promise:** The right intrusiveness is what keeps the coach switched on and the eyes off the screen.

### P1-8 · Unit tests for pace math + history codec (R7)
- **User value:** Pace you can trust release over release.
- **Effort:** M
- **Risk:** Low.
- **Dependencies:** P0-1.
- **Why it supports the promise:** The blind-trust foundation must not silently regress between versions.

---

## P2 — 1.0 public release: robustness, breadth, polish

Make it survive every real-world run, cover the workouts runners actually do, and be shippable.

### P2-1 · Workout modes: Structured + Race (intervals, warmup/work/cooldown, start tolerance)
- **User value:** Real sessions and races coached by ear — including intervals where recoveries are *meant* to be off-pace.
- **Effort:** L
- **Risk:** Med — segment model + cue scheduling complexity.
- **Dependencies:** P0-6, P1-3, P1-4.
- **Why it supports the promise:** Extends "run by ear" from steady runs to the structured training runners actually do.

### P2-2 · Treadmill mode (no GPS; manual/cadence speed)
- **User value:** Indoor runs are coached instead of silently broken.
- **Effort:** M–L
- **Risk:** Med — cadence-to-speed estimate accuracy.
- **Dependencies:** P0-6.
- **Why it supports the promise:** Brings ear-coaching to a setting where GPS and the screen can't help at all.

### P2-3 · Glance screen: pace-first color hero + glanceable status + active-run layout (U1, U2, U3)
- **User value:** When a glance *does* happen, it's instantly readable; also great portfolio screenshots.
- **Effort:** M
- **Risk:** Low.
- **Dependencies:** none.
- **Why it supports the promise:** A glance that costs a fraction of a second protects the "mostly by ear" reality instead of breaking stride.

### P2-4 · Accessibility: 48dp targets, AA contrast, content descriptions (U4, U5)
- **User value:** Usable one-handed while moving, and for Deaf/HoH (Silent profile).
- **Effort:** S–M
- **Risk:** Low.
- **Dependencies:** P2-3, P1-2.
- **Why it supports the promise:** Low-interaction, inclusive use — the app is inherently accessible to low-vision runners; this completes the picture.

### P2-5 · First-run onboarding: spoken cue legend + haptic preview + "earn the pocket" trust screen
- **User value:** Runners learn the audio/haptic language and gain the confidence to actually pocket the phone.
- **Effort:** M
- **Risk:** Low.
- **Dependencies:** P0-6, P0-7.
- **Why it supports the promise:** Discoverability is what makes the by-ear language usable — an unlearned vocabulary is just random buzzing.

### P2-6 · Battery safety: spoken low-battery warnings + power-saver mode
- **User value:** The phone (also the runner's comms/safety device) survives long runs.
- **Effort:** M
- **Risk:** Med — GPS-rate vs. accuracy trade-off.
- **Dependencies:** P0-2.
- **Why it supports the promise:** An ear-coach that kills the phone mid-run betrays the runner; graceful degradation keeps the run alive.

### P2-7 · Release packaging & light review (PK1, PK2, U6, U7, U8)
- **User value:** A shippable, polished 1.0 (real `applicationId`, custom icons), plus light post-run review and a clean codebase.
- **Effort:** M
- **Risk:** Low — `applicationId` must change **before** any external installs to avoid collisions.
- **Dependencies:** none.
- **Why it supports the promise:** Trust and distribution; review stays deliberately *light* to honor the "coach, not logger" non-goals.

### P2-8 · Quick-start widget / quick-settings tile
- **User value:** One tap to start the last plan — removes the only mandatory glance left.
- **Effort:** M
- **Risk:** Low.
- **Dependencies:** P0-4.
- **Why it supports the promise:** Directly eliminates the final "look at the phone" moment in the entire run.

---

## P3 — Post-1.0: frontier coaching + nice-to-haves

Depth that makes PacePilot a *training* coach, layered on without disturbing the core loop.

### P3-1 · Structured-workout builder + saved workout library
- **User value:** Create and reuse complex sessions.
- **Effort:** L · **Risk:** Med · **Dependencies:** P2-1.
- **Why it supports the promise:** Deepens the "real coach" identity while all guidance stays by ear.

### P3-2 · Cadence metronome (optional audio beat)
- **User value:** Hold rhythm and pace by ear with an audible beat.
- **Effort:** M · **Risk:** Low · **Dependencies:** P0-3.
- **Why it supports the promise:** A purely audio pacing aid — maximally on-mission.

### P3-3 · GPS dead-reckoning through short dropouts (cadence-based)
- **User value:** Fewer pace gaps in tunnels/underpasses.
- **Effort:** L · **Risk:** High — estimation accuracy · **Dependencies:** P2-2 (cadence sensing).
- **Why it supports the promise:** Keeps the ear-coach trustworthy exactly where GPS fails.

### P3-4 · Voice persona / stereo (L-R) correction cues
- **User value:** Richer, more intuitive audio guidance.
- **Effort:** M · **Risk:** Low · **Dependencies:** P0-6.
- **Why it supports the promise:** Refines the audio channel that *is* the product.

---

## Considered & excluded (non-goals)

Per the design doc, these are deliberately **out of scope** — listed so they don't creep back in:

| Excluded | Why (from design doc §10) |
|---|---|
| Heart-rate / effort coaching | PacePilot coaches **pace only**; it's honest about that ceiling rather than faking effort management |
| Maps / route / GPX export | Not a navigation or logging app |
| Social feed / shareable cards | Not a social platform |
| Full analytics dashboards / trends | History is light review, not the point |
| Wear OS / wrist app | The phone + earbuds *is* the watch replacement; a wrist app contradicts the premise |
| All-day fitness tracking | One job: pace coaching on a run |

---

## Suggested build sequence

1. **P0-1 first.** Responsive pace is the foundation every cue depends on — build and validate it before any audio/haptic work.
2. **P0-2 + P0-3 next** (fix speed + ducking) so there's something real and audible to coach with.
3. **P0-7 → P0-6** (haptics, then the correction loop that uses them).
4. **P0-4 / P0-5 / P0-8 / P0-9** to close the eyes-free loop and make it trustworthy → **Alpha**.
5. Layer **P1** for quality and ear-control → **Beta**; then **P2** for real-world breadth and polish → **1.0**.
