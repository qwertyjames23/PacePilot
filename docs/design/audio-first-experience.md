# PacePilot — Audio-First Experience

> **Status:** Product source of truth. This document defines *what* PacePilot is and *how it should behave*. Implementation plans and code decisions must trace back to this doc. It describes runner experience and product behavior — not code.
>
> **Last updated:** 2026-06-23

---

## 1. Product vision

PacePilot is an **audio-first running pace coach** for runners who do not own a running watch.

It exists because checking pace on a phone mid-run is disruptive: you break stride, pull the phone from your pocket, squint at a screen, and lose your rhythm. PacePilot removes that entirely. The phone stays in your pocket, earbuds in, and the app coaches you by **voice and vibration** — telling you whether you are too fast, too slow, or on target, so you can hold your goal pace without ever looking down.

PacePilot is **a coach, not a logger**. Recording the run is a side effect. The product is the guidance *during* the run.

---

## 2. Core promise

> **The runner should be able to complete an entire run without looking at the phone.**

Every design decision is judged against this single sentence. A feature that requires looking at the screen during a run is, by default, a failure of the core promise and must justify itself as an exception.

The only sanctioned glance is the one tap to start — and even that is on the roadmap to remove (quick-start widget).

### Supporting principles

1. **Silence is a feature.** When the runner is on pace, the coach says nothing and does not interrupt their music. The app earns attention only when it has something worth saying.
2. **Felt before spoken.** Minor corrections are delivered as vibration (felt against the leg) so music/podcasts are not interrupted. Voice is reserved for things worth pausing the audio for.
3. **Honest over confident.** When the app cannot see clearly (weak GPS, lost earbuds), it says so rather than coaching on bad data. A coach that confidently lies is worse than one that briefly admits uncertainty.
4. **Coach the workout, not a number.** Guidance adapts to what the runner is actually doing — warming up, climbing a hill, recovering between intervals — instead of nagging toward one flat pace.
5. **The screen earns the right to be ignored.** Early runs build trust visually; once trusted, the screen becomes a true fallback.

---

## 3. The runner journey

A complete run, end to end, designed so the runner's eyes stay on the road. Each stage is described across four lanes: **sees / hears / feels / internally**.

### Stage 1 — App launch
- **Sees:** Setup screen with the **last race plan pre-loaded**; one large *Start*; mode and listening-profile remembered from last time; hint: *"Tap Start, then pocket your phone."*
- **Hears:** Nothing — or a soft *ready* chime once GPS is warm.
- **Feels:** Nothing.
- **Internally:** Restore plan; request location permission at launch (first run only); begin GPS warmup immediately; initialize the voice engine so the first cue is never dropped.

> This is the only unavoidable glance in the whole run. It is minimized to one tap.

### Stage 2 — GPS acquisition
- **Sees (if glanced):** A calm *Acquiring GPS* state with a signal indicator.
- **Hears:** On Start: *"Acquiring GPS…"* then *"GPS ready"* the instant a usable fix lands.
- **Feels:** A distinct **rising "ready" haptic** — so the phone can be pocketed on feel alone.
- **Internally:** Force a fresh fix immediately and stream updates with no displacement filter and no batching; declare "ready" only when accuracy passes threshold; seed distance from the warmup fix.

### Stage 3 — Start countdown
- **Sees (optional):** A 3-2-1, but it is audio-driven.
- **Hears:** *"Starting in 3… 2… 1… Go. Target 7:20 per kilometre."* The gap is the window to pocket the phone.
- **Feels:** A tick per count, then a **strong "Go" buzz**.
- **Internally:** The clock and distance start at **"Go," not at tap**; a grace period begins so the coach does not bark while the runner settles in. If the workout opens with a warmup phase, this is announced as *"Warmup — keep it easy,"* with corrections off.

### Stage 4 — First kilometre
- **Sees (if glanced):** Pace and distance climbing.
- **Hears:** After grace, normal coaching engages. At 1.0 km: a km earcon then *"One kilometre. 7:14 — five seconds ahead. Next target holds at 7:20."* Target changes for the next km are announced here.
- **Feels:** The **km-complete** pattern; directional nudges if drifting.
- **Internally:** Live cues use a **responsive rolling pace** (recent ~20–30 s) so coaching can begin within seconds; the km **split** uses the precise cumulative-km average. Two pace numbers for two jobs. Race/crowd starts suppress nagging until the runner is up to speed.

### Stage 5 — Pace corrections (the core loop)
A drift-and-recover, the heartbeat of the product:
1. Runner slips to ~9 s behind → **Drifting band:** one long buzz, no voice, music untouched.
2. Fades to ~18 s behind, sustained → **Off-pace band:** music ducks, *"Push — settle into a quicker rhythm,"* with a long buzz.
3. Lifts back within ~4 s → **Back on pace:** a soft tick and a brief *"Nice — back on pace."* Then silence.

- **Sees (if glanced):** A color status — never required.
- **Hears:** Voice only for sustained/large deviations and recoveries; nothing for minor ones; phrasing encourages *gradual* adjustment.
- **Feels:** Directional haptics carry the small corrections.
- **Internally:** Rolling pace vs. the active km's target; band classification with **hysteresis** (must be off for several seconds before escalating, and back in-band before "back on pace"); **terrain tolerance** widens the band when swings look like hills; cadence governor prevents nagging; corrections never interrupt a split.

### Stage 6 — Pauses
- **Sees:** A paused state.
- **Hears:** *"Paused"* / *"Resumed."* At a stoplight, **auto-pause**: *"Auto-paused"* → first steps again → *"Resumed."*
- **Feels:** The alert pattern on pause; soft tick on resume.
- **Internally:** Moving-time clock stops, corrections suspend, the service stays alive; movement detection drives auto-resume, debounced so a slow uphill grind is not mistaken for stopping. Manual pause is reachable eyes-free (earbud/notification). Sustained inactivity triggers an **auto-stop** prompt to prevent dead-battery junk runs.

### Stage 7 — Signal loss / channel loss
- **Sees (if glanced):** Signal indicator drops.
- **Hears:** *"GPS signal weak — holding pace,"* then *"Signal back."* Corrections go quiet during loss rather than firing on garbage data. If earbuds disconnect: fall back to haptics (and optionally speaker); on reconnect, *"Audio back."* If a call or another app seizes audio: cues suspend and resume afterward.
- **Feels:** The alert pattern (distinct from any correction).
- **Internally:** Monitor accuracy continuously; freeze live pace and suppress cues on degradation; never accumulate junk distance; auto-resume when accuracy returns; detect headset/audio-focus changes and route around them.

### Stage 8 — Status checks (on demand)
- **Sees:** Nothing — this replaces taking the phone out.
- **Hears:** On trigger: *"2.4 K, 17 minutes 50, current pace 7:18, on target."*
- **Feels:** A small ack tick confirming the trigger registered.
- **Internally:** Triggered by **reliable controls** — lockscreen/notification button, optional paired BT remote, or a guaranteed in-app button. (Shake is **not** used — running motion false-fires it.) Proactive periodic stats reduce how often the runner needs to ask. Queued so it never collides with a cue mid-sentence.

### Stage 9 — Run completion
- **Sees:** A summary screen for later review.
- **Hears:** *"Run complete. 5.2 kilometres in 38 minutes, average 7:18. Four of five kilometres on target. Nice work."* Structured workouts get a rep-by-rep breakdown.
- **Feels:** A celebratory completion pattern.
- **Internally:** Stopping is eyes-free (earbud/notification long-press with audio confirm and an accidental-stop guard); finalize splits/averages; **skip saving junk runs**; release audio focus; stop the foreground service cleanly.

### The "never look at the phone" scorecard

| Moment | Old way (look) | Audio-first way |
|---|---|---|
| Start | Watch for GPS lock | Hear *"GPS ready,"* feel the ready buzz |
| Pace check | Pull phone out | Felt nudges + status on demand |
| Am I on target? | Read the screen | Silence = fine; voice/haptic = act |
| Pause at a light | Tap screen | Auto-pause + audio confirm |
| Lost signal? | Notice stale data | *"Signal weak"* + alert haptic |
| Finished a km? | Glance | Km earcon + spoken split |
| End the run | Tap Stop, read summary | Earbud long-press + spoken summary |

---

## 4. Coaching philosophy

PacePilot coaches the way a good human coach does: **present when needed, silent when not, and honest about what it can and cannot see.**

- **Coach effort and the workout, not a flat number.** Warmups are meant to be slow; hills change pace; interval recoveries are deliberately off-target. The coach respects all of this instead of nagging toward one pace.
- **Tolerate small drift; correct gradually.** Pace naturally fluctuates. Precise, aggressive corrections cause novices to surge, overshoot, and yo-yo. Guidance nudges gently and waits to see a trend before escalating (hysteresis).
- **Ranges, not points.** Targets are bands, and the band width is a coaching choice: wide and quiet on easy days, tight on tempo days.
- **Reinforce the good.** When the runner corrects back onto pace, acknowledge it once, then go quiet.
- **Set honest expectations.** PacePilot is a **pace** coach. It has no heart-rate or effort sensor and runs on phone GPS (noisier than a wrist watch, especially in a pocket). It will not pretend to manage effort, heat, or fatigue, and it warns when its data is unreliable.

### The deviation bands

| Band | Off target by | Channel | Why |
|---|---|---|---|
| **On pace** | within tolerance | Silence (music untouched) | Don't nag when fine |
| **Drifting** | just past tolerance | **Haptic only** | Felt; doesn't interrupt audio |
| **Off pace** | well past tolerance, sustained | **Voice + haptic** | Worth interrupting for |

Tolerance and band widths are set by workout mode and listening profile, with hysteresis on every edge to prevent chatter.

---

## 5. Listening profiles

The runner chooses how intrusive the coach is. The profile is remembered between runs.

| Profile | Behavior | Best for |
|---|---|---|
| **Coach** (default) | Full voice + haptics. Spoken corrections, splits, milestones, alerts. | Music listeners who want active guidance |
| **Minimal** | **Haptic-first.** Voice reserved for splits, alerts, and on-demand status. Almost no music ducking. | Podcast / audiobook listeners |
| **Silent** | Haptics + screen only; no audio at all. Requires the full haptic vocabulary and a proactive screen. | Deaf / hard-of-hearing runners; quiet environments |

Additional cross-cutting controls:
- **Chattiness** — how often the coach speaks.
- **Band width / strictness** — how much drift is tolerated before a cue.
- **Vibration strength** — for pocket vs. jacket vs. belt placement.
- **Units** — kilometres or miles, applied to both speech and screen.

---

## 6. Workout modes

A single flat target gives wrong advice on hills, warmups, and intervals. Modes fix this at the root. The mode is chosen at setup and remembered.

| Mode | Behavior |
|---|---|
| **Free run** | No correction nagging; periodic spoken stats only. |
| **Goal pace** | A target pace (optionally per-kilometre), with explicit warmup/cooldown phases where slow is expected and not corrected. |
| **Structured workout** | Warmup → reps → recoveries → cooldown. Per-segment targets, time- *or* distance-based reps, spoken rep/rest calls (*"Rep 3, 400 metres — go" / "Recover, 200 metres jog"*). **No correction during recovery.** This is the path from "pace governor" to real coach. |
| **Race** | Conservative-start tolerance (no nagging until up to speed); per-km plan supports negative splits. |
| **Treadmill** | **No GPS.** Coaching runs off manually entered speed (or cadence-based estimate). The full coaching loop still works — rescues a use case where GPS produces zero movement. |

---

## 7. Haptic language

The phone in a pocket sits against the leg, making vibration a **primary** channel — not a backup. The vocabulary is small enough to learn once and is taught on the first run (spoken legend + cue-preview screen).

| Pattern | Meaning | Mnemonic |
|---|---|---|
| One long buzz | Too slow — pick it up | slow = one heavy pulse |
| Two quick buzzes | Too fast — ease off | fast = hurried double |
| One soft tick | Back on pace (positive) | the "good" tap |
| Triple ascending | Kilometre / segment complete | a little celebration |
| Strong single, rising | "Go" / GPS ready | the green light |
| Distinct off-beat double | Alert (signal loss, auto-pause, low battery) | "something changed" |

Principles:
- **Direction is encoded in the pattern**, so the runner knows *what to do* without audio.
- **Critical info is never haptic-only** — key events also get a short earcon (where audio is available), because pockets and jackets muffle vibration.
- **Strength is user-adjustable** for different carry positions.
- In **Silent** profile, haptics carry the full load and the vocabulary is the entire interface.

---

## 8. Audio cue philosophy

Audio is **expensive attention** — every spoken cue interrupts the runner's music or podcast. It must be earned.

- **Two kinds of audio:**
  - **Earcons** (short non-verbal tones) for frequent, low-information events (km marker, ack tick) — minimal interruption.
  - **Voice** for information-rich events (magnitude corrections, splits, alerts, summaries).
- **Tone:** brief, imperative, encouraging — a coach, not a robot. Lead with *direction and intensity* ("ease off," "push gradually"); include the number only when it helps.
- **Ducking:** music dips for a cue, then restores. In **Minimal** profile, ducking is avoided wherever possible so podcasts stay intact.
- **Cadence governor:** announce on a *change*, then go quiet; remind only at widening intervals if uncorrected; never stack cues.
- **Priority queue:** **alerts** (signal / battery / auto-pause) > **splits** > **corrections** > **on-demand status**. Stale corrections are dropped rather than spoken late.
- **Never interrupt a split** with a correction; never talk over the runner's own on-demand request.
- **Proactive over reactive:** periodic stats (per km / per few minutes) reduce how often the runner must ask.

---

## 9. Failure handling

The core promise makes *silent* failures the worst kind — an app whose whole value is "don't look at the phone" must never fail quietly.

| Scenario | Behavior |
|---|---|
| **Weak / lost GPS** | Announce *"Signal weak — holding pace"*; freeze live pace; suppress corrections; never accumulate junk distance; announce *"Signal back"* on recovery. |
| **Headphone disconnect** | Detect it; fall back to haptics (and optionally speaker); announce *"Audio back"* on reconnect. Never coach into a dead channel. |
| **Call / other-app audio** | Suspend cues during audio-focus loss; resume afterward with a brief confirmation. |
| **Treadmill (no GPS)** | Treadmill mode runs coaching off entered/sensed speed instead of failing. |
| **Crowded race start** | Suppress nagging until up to speed; tolerate weaving/GPS noise. |
| **Hills / variable terrain** | Terrain tolerance widens the band and reduces cue frequency; effort, not flat pace. |
| **Warmup / cooldown** | Explicit phases where slow is expected; no correction. |
| **Noisy roads / safety** | Keep cues short; recommend one earbud / transparency so traffic stays audible. |
| **Low battery** | Spoken warnings at thresholds; offer power-saver (lower GPS rate, screen off, fewer cues). |
| **Forgot to stop** | Sustained inactivity → auto-stop prompt → pause and save; prevents dead-battery junk runs. |
| **Junk run (mis-tap)** | Runs below a minimum distance/time are not saved. |
| **First-run trust** | Reassuring screen + spoken cue legend, so the runner learns the language and earns the confidence to pocket the phone. |

---

## 10. Non-goals

PacePilot deliberately does **not** try to be these things. Saying no here keeps the product focused on its promise.

- **Not a social / activity-sharing platform.** No feed, no followers, no shareable cards as a core feature.
- **Not a run-logging / analytics suite.** History exists for light review, not deep dashboards, trends, or charts-as-the-point.
- **Not a maps / navigation app.** No route plotting or turn-by-turn.
- **Not a heart-rate / effort coach.** No HR, no VO2, no training-load modeling. It is honest that it coaches **pace only**.
- **Not a watch / wearable app.** The phone-plus-earbuds *is* the watch replacement; building a wrist app contradicts the premise.
- **Not a screen-first experience.** The screen is a trust-builder and fallback, never the primary interface during a run.
- **Not a general fitness tracker.** No steps-all-day, sleep, calories, or cross-sport tracking. One job: pace coaching on a run.

> Anything proposed for PacePilot must serve the core promise — *complete a run without looking at the phone* — or it does not belong here.
