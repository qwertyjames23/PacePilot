# Running App Enhancement Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix GPS slow acquisition, fix per-km pace voice announcement, and redesign the UI to be professional and user-friendly.

**Architecture:** Three isolated changes to two files — `MainActivity.kt` (UI + GPS warmup) and `LocationService.kt` (GPS request + voice logic). No new files needed. All UI is Kotlin View code (no XML, no Compose).

**Tech Stack:** Kotlin, Android Views (LinearLayout-based), FusedLocationProviderClient, TextToSpeech

---

## ISSUES BEING FIXED

1. **GPS slow fix** — `startGpsWarmup()` uses 5000ms interval. Fix: call `getLastLocation()` immediately (cache hit = instant) + reduce warmup to 1000ms + add `setWaitForAccurateLocation(false)` in service.
2. **Per-km voice bug** — `handleKilometerVoice()` says the COMPLETED km's target, not the NEXT km's target. Runner hears "7:30" after km 1 and thinks nothing changed, even though 7:00 is now active.
3. **UI redesign** — Replace bare EditText pace inputs with nudge-button rows (`[−5s] 7:30 [+5s]`), add a visual km progress bar, cleaner layout, professional typography.

---

## Task 1: GPS Fast Acquisition

**Files:**
- Modify: `app/src/main/java/com/example/runningpace/MainActivity.kt` (startGpsWarmup)
- Modify: `app/src/main/java/com/example/runningpace/LocationService.kt` (buildLocationRequest, startTracking)

### Step 1: Fix warmup in MainActivity.kt

Find `startGpsWarmup()` (around line 552). Replace entirely:

```kotlin
@SuppressLint("MissingPermission")
private fun startGpsWarmup() {
    if (warmupActive || currentTracking) return
    if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return

    // Immediately grab last known location for near-instant first fix
    warmupClient.lastLocation.addOnSuccessListener { loc ->
        // loc may be null if device never had a fix — that's fine
    }

    val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)  // ← key: don't wait, give first fix fast
            .build()
    } else {
        @Suppress("DEPRECATION")
        LocationRequest.create().apply {
            interval = 1000L
            fastestInterval = 500L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }
    warmupClient.requestLocationUpdates(request, warmupCallback, Looper.getMainLooper())
    warmupActive = true
}
```

### Step 2: Fix buildLocationRequest() in LocationService.kt

Find `buildLocationRequest()` (around line 169). Add `setWaitForAccurateLocation(false)` to the Android S+ branch:

```kotlin
private fun buildLocationRequest(): LocationRequest {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(5f)
            .setMaxUpdateDelayMillis(3000L)
            .setWaitForAccurateLocation(false)  // ← add this line
            .build()
    } else {
        @Suppress("DEPRECATION")
        LocationRequest.create().apply {
            interval = 2000L
            fastestInterval = 1000L
            smallestDisplacement = 5f
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }
}
```

### Step 3: Feed last known location into calculator at run start

In `LocationService.kt`, add after `requestLocationUpdates()` in `startTracking()`:

```kotlin
@SuppressLint("MissingPermission")
private fun startTracking() {
    // ... existing reset code stays ...

    startForeground(NOTIFICATION_ID, buildNotification(latest))
    requestLocationUpdates()

    // Seed calculator with last known location for immediate pace display
    if (hasFinePermission()) {
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && isTracking && !isPaused) {
                latest = calculator.onLocation(loc)
                publishMetrics(latest)
            }
        }
    }

    publishMetrics(latest)
}
```

### Step 4: Build and verify no compile errors

Build the project. There should be no new errors — these are additive changes only.

---

## Task 2: Fix Per-km Voice Announcement

**Files:**
- Modify: `app/src/main/java/com/example/runningpace/LocationService.kt` (handleKilometerVoice)

### Step 1: Understand current behavior vs desired

**Current:** "1 kilometer completed. Current pace 7:35. Target pace 7 minutes 30 seconds. 5 seconds behind target."
→ Runner hears "7:30" and thinks target unchanged.

**Fixed:** "KM 1 done. Pace 7:35. 5 seconds behind target. KM 2 target: 7 minutes 0 seconds."
→ Runner immediately knows the next target.

### Step 2: Replace handleKilometerVoice in LocationService.kt

Find `handleKilometerVoice` (around line 251). Replace entirely:

```kotlin
private fun handleKilometerVoice(completedKm: Int, currentPaceSec: Double) {
    if (completedKm <= lastSpokenKm) return

    for (km in (lastSpokenKm + 1)..completedKm) {
        val completedTarget = targetForKilometer(km)
        val nextTarget = targetForCurrentKilometer(km)  // target for km+1

        val sb = StringBuilder()
        sb.append("$km kilometer completed. ")

        if (!currentPaceSec.isNaN()) {
            sb.append("Pace ${paceForSpeech(currentPaceSec)}. ")
            sb.append("${paceDeltaForSpeech(currentPaceSec, completedTarget)}. ")
        }

        // Announce next km's target so runner knows what to aim for NOW
        sb.append("KM ${km + 1} target: ${paceForSpeech(nextTarget)}.")

        tts?.speak(sb.toString(), TextToSpeech.QUEUE_ADD, null, "km_$km")
    }
    lastSpokenKm = completedKm
}
```

### Step 3: Build and verify no compile errors

---

## Task 3: UI Redesign

**Files:**
- Modify: `app/src/main/java/com/example/runningpace/MainActivity.kt`

This is the largest task. The full `MainActivity.kt` content below is the complete replacement. The changes are:

1. **Pace input rows** — each km row gets `[−]  7:30  [+]` nudge buttons (+/- 5 seconds). No more typing mm:ss from scratch — just nudge from the default.
2. **Race plan section** — renamed from "Per-km target pace", cleaner visual
3. **Km progress bar** — replaces the plain text "850 / 1000 m"
4. **Pace zone indicator** — visual bar showing position between too-fast/on-target/too-slow
5. **Status chip** — pill shape with dot indicator
6. **Color refinement** — same dark palette, cleaner accent use
7. **Button refinement** — rounded, better contrast

### Step 1: Add paceRowPaceSec map and helpers at the top of MainActivity

Add these new fields after the existing `private val kmTargetRows` declaration (around line 89):

```kotlin
// Stores current pace in seconds for each km row (for nudge buttons)
private val kmRowPaceSecs = linkedMapOf<Int, Int>()
```

### Step 2: Add createPaceInputRow helper method

Add this new private method before `addKmTargetField`:

```kotlin
private fun createPaceInputRow(km: Int): LinearLayout {
    val defaultPaceSec = kmRowPaceSecs.getOrPut(km) {
        // Default: km 1 inherits targetInput value, others start at 7:00
        if (km == 1) 7 * 60 + 20 else 7 * 60
    }

    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
    }

    val label = TextView(this).apply {
        text = "KM $km"
        setTextColor(colorTextSecondary)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    val display = TextView(this).apply {
        text = secsToPaceString(kmRowPaceSecs[km] ?: defaultPaceSec)
        setTextColor(colorTextPrimary)
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    // Store reference so parseKmTargetsFromBoxes can still read it
    // We repurpose kmPaceInputs[km] but use an invisible EditText as backing store
    val hiddenInput = EditText(this).apply {
        visibility = View.GONE
        setText(secsToPaceString(kmRowPaceSecs[km] ?: defaultPaceSec))
    }

    fun nudge(deltaSec: Int) {
        val current = kmRowPaceSecs[km] ?: defaultPaceSec
        val updated = (current + deltaSec).coerceIn(120, 1800)
        kmRowPaceSecs[km] = updated
        display.text = secsToPaceString(updated)
        hiddenInput.setText(secsToPaceString(updated))
    }

    val minusBtn = Button(this).apply {
        text = "−"
        textSize = 18f
        isAllCaps = false
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(colorTextSecondary)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#1A2537"))
            setStroke(dp(1), colorCardBorder)
        }
        setOnClickListener { nudge(-5) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
    }

    val plusBtn = Button(this).apply {
        text = "+"
        textSize = 18f
        isAllCaps = false
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(colorAccent)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#0D2420"))
            setStroke(dp(1), colorAccent)
        }
        setOnClickListener { nudge(+5) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
    }

    row.addView(label)
    row.addView(minusBtn)
    row.addView(display)
    row.addView(plusBtn)
    row.addView(hiddenInput)  // invisible, just for parsing

    kmPaceInputs[km] = hiddenInput
    return row
}

private fun secsToPaceString(secs: Int): String {
    val m = secs / 60
    val s = secs % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
```

### Step 3: Update addKmTargetField to use the new row design

Replace `addKmTargetField` entirely:

```kotlin
private fun addKmTargetField(km: Int) {
    if (kmPaceInputs.containsKey(km)) return

    val outerRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val paceRow = createPaceInputRow(km)
    paceRow.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    val deleteButton = Button(this).apply {
        text = "✕"
        textSize = 13f
        isAllCaps = false
        setTextColor(colorTextSecondary)
        minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#1A1A2A"))
        }
        setOnClickListener { removeKmTargetField(km) }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginStart = dp(8)
        }
    }

    outerRow.addView(paceRow)
    if (km > 1) outerRow.addView(deleteButton)  // km 1 cannot be deleted

    kmTargetsContainer.addView(outerRow)
    kmTargetRows[km] = outerRow as LinearLayout
    updateNextKmTarget()
}
```

> **Note:** `kmTargetRows` stores `LinearLayout` — update its type to `LinkedHashMap<Int, LinearLayout>` if needed (it already is).

### Step 4: Update buildUi() — Setup Section Header

Find the `kmTargetsTitle` TextView (around line 249). Update its text and style:

```kotlin
val kmTargetsTitle = TextView(this).apply {
    text = "RACE PLAN  •  per km"
    setTextColor(colorTextSecondary)
    textSize = 11f
    letterSpacing = 0.10f
    setTypeface(typeface, Typeface.BOLD)
}
```

### Step 5: Update buildUi() — Add KM button text and style

Find `addKmTargetButton` (around line 255). Update:

```kotlin
addKmTargetButton = Button(this).apply {
    text = "+ Add KM"
    isAllCaps = false
    textSize = 14f
    setTypeface(Typeface.DEFAULT_BOLD)
    setTextColor(colorAccent)
    styleButton(this, Color.parseColor("#0A1E1A"), colorAccent, borderColor = withAlpha(colorAccent, 0.4f))
    setOnClickListener { addKmTargetField(nextKmTarget) }
}
```

### Step 6: Update buildUi() — Add km progress bar

Replace the `kmProgressText` TextView with a layout that includes a progress bar. Add a new field:

```kotlin
private lateinit var kmProgressBar: android.widget.ProgressBar
private lateinit var kmProgressLabel: TextView
```

In `buildUi()`, replace the `kmProgressText` creation:

```kotlin
// KM progress row with bar
val kmProgressRow = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    visibility = View.GONE
}
kmProgressLabel = TextView(this).apply {
    text = ""
    setTextColor(colorTextSecondary)
    textSize = 12f
    letterSpacing = 0.04f
}
kmProgressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 1000
    progress = 0
    progressDrawable = android.graphics.drawable.LayerDrawable(
        arrayOf(
            GradientDrawable().apply { setColor(Color.parseColor("#1A253A")) },
            GradientDrawable().apply { setColor(colorAccent) }
        )
    )
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
    ).apply { topMargin = dp(6) }
}
kmProgressRow.addView(kmProgressLabel)
kmProgressRow.addView(kmProgressBar)
// keep kmProgressText for compatibility (GONE, never shown)
kmProgressText = TextView(this).apply { visibility = View.GONE }
```

And in the metrics card assembly, replace the `addWithTopMargin(metricsCard, kmProgressText, dp(12))` line with:

```kotlin
addWithTopMargin(metricsCard, kmProgressRow, dp(14))
```

Store `kmProgressRow` as a field: `private lateinit var kmProgressRow: LinearLayout`

### Step 7: Update metricsReceiver to drive the progress bar

In `metricsReceiver`, find the block that sets `kmProgressText` (around line 160). Replace with:

```kotlin
if (tracking && !pace.isNaN()) {
    val currentKm = completedKm + 1
    val progressMeters = kmProgress.toInt().coerceIn(0, 1000)
    kmProgressLabel.text = "KM $currentKm  •  ${progressMeters}m / 1000m"
    kmProgressBar.progress = progressMeters
    kmProgressRow.visibility = View.VISIBLE
} else {
    kmProgressRow.visibility = View.GONE
}
```

Also update `updateButtons` to hide `kmProgressRow` when stopped:
```kotlin
// In the !tracking branch:
kmProgressRow.visibility = View.GONE
```

### Step 8: Update buildUi() — Pace zone indicator bar

Add a pace zone view below the status chip. Add field:
```kotlin
private lateinit var paceZoneBar: View
private lateinit var paceZoneIndicator: View
```

After `stateText` in the root layout assembly, add a narrow zone bar:

```kotlin
// Pace zone bar: [TOO FAST] ←→ [ON TARGET] ←→ [TOO SLOW]
val zoneContainer = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    visibility = View.GONE  // shown only when running
}
// We'll use a simple 3-section colored bar with labels
val zoneFast = TextView(this).apply {
    text = "FAST"
    setTextColor(withAlpha(colorWarn, 0.7f))
    textSize = 10f
    letterSpacing = 0.08f
}
val zoneTarget = TextView(this).apply {
    text = "ON TARGET"
    setTextColor(withAlpha(colorAccent, 0.9f))
    textSize = 10f
    letterSpacing = 0.08f
    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
    gravity = Gravity.CENTER
}
val zoneSlow = TextView(this).apply {
    text = "SLOW"
    setTextColor(withAlpha(colorDanger, 0.7f))
    textSize = 10f
    letterSpacing = 0.08f
}
zoneContainer.addView(zoneFast)
zoneContainer.addView(zoneTarget)
zoneContainer.addView(zoneSlow)
```

Store `zoneContainer` and show/hide it with `stateText`. Update `updateButtons` to show it when tracking.

### Step 9: Update buildUi() — app header

Replace the `headerTitle` text styling for a more premium look:

```kotlin
val headerDot = TextView(this).apply {
    text = "●"
    setTextColor(colorAccent)
    textSize = 12f
    setPadding(0, 0, dp(8), 0)
}
val headerTitle = TextView(this).apply {
    text = "PacePilot"
    setTextColor(colorTextPrimary)
    textSize = 28f
    setTypeface(Typeface.DEFAULT_BOLD)
    letterSpacing = -0.02f
}
val headerSub = TextView(this).apply {
    text = "Smart Running Pace Guide"
    setTextColor(withAlpha(colorTextSecondary, 0.7f))
    textSize = 12f
    letterSpacing = 0.04f
}
val headerRow = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(headerDot)
    addView(headerTitle)
}
val headerBlock = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(headerRow)
    addView(headerSub)
}
```

In root layout, replace `root.addView(headerTitle)` with `root.addView(headerBlock)`.

### Step 10: Update restoreTargetInputs to use kmRowPaceSecs

In `restoreTargetInputs()`, after `kmPaceInputs[km]?.setText(raw)`, add:

```kotlin
// Also sync kmRowPaceSecs from restored value
parsePaceSeconds(raw)?.toInt()?.let { kmRowPaceSecs[km] = it }
```

And when `targetInput` value is restored, also seed km 1:
```kotlin
parsePaceSeconds(baseRaw)?.toInt()?.let { kmRowPaceSecs[1] = it }
```

### Step 11: Update km 1 input to also use nudge buttons

The existing `targetInput` EditText for "1 km" can be replaced by the new nudge row style. Replace `targetInput` EditText setup in `buildUi()`:

```kotlin
// Replace the targetInput EditText with a nudge-style row for KM 1
// km 1 row is driven by targetInput backing store
targetInput = EditText(this).apply {
    visibility = View.GONE  // hidden, used only as backing store
    setText("7:20")
}
kmRowPaceSecs[1] = parsePaceSeconds("7:20")?.toInt() ?: (7 * 60 + 20)
val km1Row = createPaceInputRow(1)  // uses kmRowPaceSecs[1]
// kmPaceInputs[1] now contains the hidden EditText from createPaceInputRow
```

> The `parseKmTargetsFromBoxes` already reads `kmPaceInputs[1]` but **wait** — currently km 1 is passed as `firstKmTargetSec` parameter NOT from `kmPaceInputs`. Update `onStartClicked`:

```kotlin
private fun onStartClicked() {
    // Read km 1 from the nudge row's backing hidden input, same as others
    val km1Input = kmPaceInputs[1]
    val targetSec = if (km1Input != null) {
        parsePaceSeconds(km1Input.text.toString())
    } else {
        parsePaceSeconds(targetInput.text.toString())
    }
    if (targetSec == null) {
        Toast.makeText(this, "KM 1 pace is invalid.", Toast.LENGTH_LONG).show()
        return
    }
    // Build map: km1 from targetSec, rest from kmPaceInputs
    val kmTargets = parseKmTargetsFromBoxes(targetSec)
    if (kmTargets == null) {
        Toast.makeText(this, "Invalid pace in km target boxes.", Toast.LENGTH_LONG).show()
        return
    }
    val kmTargetsEncoded = encodeKmTargets(kmTargets)
    // ... rest unchanged
}
```

### Step 12: Build and test end-to-end

1. Build project — fix any compile errors
2. Run on device/emulator
3. Verify:
   - App opens, GPS warms up faster (check logcat for location updates)
   - Race plan shows km 1, 2, 3 with nudge buttons
   - Tapping − and + changes displayed pace by 5 seconds
   - Tapping Start Run starts the run with correct targets
   - During run, km progress bar fills as runner covers distance
   - When km 1 completes, voice says "KM 1 done... KM 2 target: X"
   - Target text in UI changes as km milestones pass

---

## Quick Reference: Key Constants

```
colorAccent    = #00D4C8  (teal)
colorWarn      = #FFB347  (amber — too fast)
colorDanger    = #FF6B6B  (red — too slow)
colorCard      = #141D2E
colorCardBorder= #253554
colorTextPrimary  = #F2F7FF
colorTextSecondary= #9CB1CF
```

---

## Commit Points

After Task 1 (GPS): `git add -A && git commit -m "fix: faster GPS acquisition with lastLocation seed and 1s warmup"`

After Task 2 (Voice): `git add -A && git commit -m "fix: voice announces next km target after each km completion"`

After Task 3 (UI): `git add -A && git commit -m "feat: redesign UI with nudge pace inputs, progress bar, and cleaner layout"`
