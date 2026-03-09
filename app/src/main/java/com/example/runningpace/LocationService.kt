package com.example.runningpace

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlin.math.abs

class LocationService : Service(), TextToSpeech.OnInitListener {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator
    private lateinit var calculator: PaceCalculator
    private var tts: TextToSpeech? = null

    private var isTracking = false
    private var isPaused = false
    private var targetPaceSec = DEFAULT_TARGET_PACE_SEC
    private var kmTargetOverrides: Map<Int, Double> = emptyMap()
    private var lastFeedbackMs = 0L
    private var lastVoiceFeedbackMs = 0L
    private var lastVoiceState = PaceState.UNKNOWN
    private var lastSpokenKm = 0
    private var latest = PaceSnapshot(0.0, 0.0, Double.NaN, 0)
    private var runStartedAtEpochMs = 0L
    private var runStartedAtElapsedMs = 0L
    private var pausedAccumulatedMs = 0L
    private var pausedStartedAtElapsedMs = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!isTracking || isPaused) return
            for (loc in result.locations) {
                latest = calculator.onLocation(loc)
                val activeTarget = targetForCurrentKilometer(latest.completedKilometers)
                handlePaceFeedback(latest.paceSecPerKm, activeTarget)
                handleKilometerVoice(latest.completedKilometers, latest.paceSecPerKm)
                updateNotification(latest)
                publishMetrics(latest, activeTarget)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        calculator = PaceCalculator()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        tts = TextToSpeech(applicationContext, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetPaceSec = intent.getDoubleExtra(EXTRA_TARGET_PACE_SEC, targetPaceSec)
                if (intent.hasExtra(EXTRA_KM_TARGETS)) {
                    kmTargetOverrides = decodeKmTargets(intent.getStringExtra(EXTRA_KM_TARGETS))
                }
                if (!isTracking) {
                    startTracking()
                } else {
                    updateNotification(latest)
                    publishMetrics(latest)
                }
            }

            ACTION_UPDATE_TARGET -> {
                targetPaceSec = intent.getDoubleExtra(EXTRA_TARGET_PACE_SEC, targetPaceSec)
                if (intent.hasExtra(EXTRA_KM_TARGETS)) {
                    kmTargetOverrides = decodeKmTargets(intent.getStringExtra(EXTRA_KM_TARGETS))
                }
                updateNotification(latest)
                publishMetrics(latest)
            }

            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
            ACTION_STOP -> stopTracking(saveRun = true)
            else -> if (!isTracking) startTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (!hasFinePermission()) {
            stopSelf()
            return
        }

        isTracking = true
        isPaused = false
        calculator.reset()
        latest = PaceSnapshot(0.0, 0.0, Double.NaN, 0)
        lastFeedbackMs = 0L
        lastVoiceFeedbackMs = 0L
        lastVoiceState = PaceState.UNKNOWN
        lastSpokenKm = 0
        runStartedAtEpochMs = System.currentTimeMillis()
        runStartedAtElapsedMs = SystemClock.elapsedRealtime()
        pausedAccumulatedMs = 0L
        pausedStartedAtElapsedMs = 0L

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

    private fun pauseTracking() {
        if (!isTracking || isPaused) return
        fused.removeLocationUpdates(callback)
        isPaused = true
        pausedStartedAtElapsedMs = SystemClock.elapsedRealtime()
        updateNotification(latest)
        publishMetrics(latest)
    }

    private fun resumeTracking() {
        if (!isTracking || !isPaused) return
        val now = SystemClock.elapsedRealtime()
        pausedAccumulatedMs += (now - pausedStartedAtElapsedMs).coerceAtLeast(0L)
        pausedStartedAtElapsedMs = 0L
        isPaused = false
        requestLocationUpdates()
        updateNotification(latest)
        publishMetrics(latest)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (!hasFinePermission()) return
        fused.requestLocationUpdates(
            buildLocationRequest(),
            callback,
            Looper.getMainLooper()
        )
    }

    private fun buildLocationRequest(): LocationRequest {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(5f)
                .setMaxUpdateDelayMillis(3000L)
                .setWaitForAccurateLocation(false)
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

    // Vibrate + speak cues when outside target pace window.
    private fun handlePaceFeedback(currentPaceSec: Double, activeTargetPaceSec: Double) {
        if (currentPaceSec.isNaN()) return

        val now = SystemClock.elapsedRealtime()
        val diff = currentPaceSec - activeTargetPaceSec
        val state = when {
            abs(diff) <= PACE_TOLERANCE_SECONDS -> PaceState.ON_TARGET
            diff > 0.0 -> PaceState.TOO_SLOW
            else -> PaceState.TOO_FAST
        }

        if (state == PaceState.ON_TARGET) {
            lastVoiceState = PaceState.ON_TARGET
            return
        }

        if (now - lastFeedbackMs < FEEDBACK_COOLDOWN_MS) return
        lastFeedbackMs = now

        if (state == PaceState.TOO_SLOW) {
            vibrateTooSlow()
            maybeSpeakPaceCue("Speed up a little.", state, now)
        } else {
            vibrateTooFast()
            maybeSpeakPaceCue("Slow down a little.", state, now)
        }
    }

    private fun maybeSpeakPaceCue(message: String, state: PaceState, nowMs: Long) {
        val cooldown = if (state != lastVoiceState) {
            VOICE_STATE_CHANGE_COOLDOWN_MS
        } else {
            VOICE_REPEAT_COOLDOWN_MS
        }
        if (nowMs - lastVoiceFeedbackMs < cooldown) return

        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "pace_${state.name}_$nowMs")
        lastVoiceFeedbackMs = nowMs
        lastVoiceState = state
    }

    private fun vibrateTooSlow() {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(120L)
        }
    }

    private fun vibrateTooFast() {
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 60L, 90L, 60L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    // Speak once per completed kilometer.
    private fun handleKilometerVoice(completedKm: Int, currentPaceSec: Double) {
        if (completedKm <= lastSpokenKm) return

        for (km in (lastSpokenKm + 1)..completedKm) {
            val kmTarget = targetForKilometer(km)
            val message = if (currentPaceSec.isNaN()) {
                "$km kilometer completed. Target pace ${paceForSpeech(kmTarget)}."
            } else {
                "$km kilometer completed. Current pace ${paceForSpeech(currentPaceSec)}. " +
                    "Target pace ${paceForSpeech(kmTarget)}. " +
                    "${paceDeltaForSpeech(currentPaceSec, kmTarget)}."
            }
            tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "km_$km")
        }
        lastSpokenKm = completedKm
    }

    private fun paceDeltaForSpeech(currentPaceSec: Double, targetSec: Double): String {
        val diff = currentPaceSec - targetSec
        if (abs(diff) <= PACE_TOLERANCE_SECONDS) return "On target"

        val deltaSec = abs(diff).toInt().coerceAtLeast(1)
        val direction = if (diff > 0.0) "behind target" else "ahead of target"
        return "$deltaSec seconds $direction"
    }

    private fun paceForSpeech(secPerKm: Double): String {
        val sec = secPerKm.toInt().coerceAtLeast(0)
        val min = sec / 60
        val rem = sec % 60
        return "$min minutes $rem seconds per kilometer"
    }

    private fun targetForCurrentKilometer(completedKm: Int): Double {
        val currentKm = (completedKm + 1).coerceAtLeast(1)
        return targetForKilometer(currentKm)
    }

    private fun targetForKilometer(km: Int): Double {
        if (kmTargetOverrides.isEmpty()) return targetPaceSec
        kmTargetOverrides[km]?.let { return it }

        val fallbackKm = kmTargetOverrides.keys.filter { it < km }.maxOrNull()
        return if (fallbackKm != null) {
            kmTargetOverrides.getValue(fallbackKm)
        } else {
            targetPaceSec
        }
    }

    private fun decodeKmTargets(encoded: String?): Map<Int, Double> {
        if (encoded.isNullOrBlank()) return emptyMap()

        val parsed = linkedMapOf<Int, Double>()
        val entries = encoded.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        for (entry in entries) {
            val parts = entry.split(":", limit = 2)
            if (parts.size != 2) continue

            val km = parts[0].trim().toIntOrNull() ?: continue
            val paceSec = parts[1].trim().toIntOrNull() ?: continue
            if (km <= 0 || paceSec <= 0) continue

            parsed[km] = paceSec.toDouble()
        }
        return parsed.toSortedMap()
    }

    private fun publishMetrics(
        snapshot: PaceSnapshot,
        activeTargetPaceSec: Double = targetForCurrentKilometer(snapshot.completedKilometers)
    ) {
        val intent = Intent(ACTION_METRICS).apply {
            setPackage(packageName)
            putExtra(EXTRA_DISTANCE_METERS, snapshot.distanceMeters)
            putExtra(EXTRA_SPEED_KMH, snapshot.speedKmh)
            putExtra(EXTRA_PACE_SEC_PER_KM, snapshot.paceSecPerKm)
            putExtra(EXTRA_TARGET_PACE_SEC, activeTargetPaceSec)
            putExtra(EXTRA_IS_TRACKING, isTracking)
            putExtra(EXTRA_IS_PAUSED, isPaused)
            putExtra(EXTRA_ELAPSED_MS, currentMovingDurationMs())
            putExtra(EXTRA_COMPLETED_KM, snapshot.completedKilometers)
            putExtra(EXTRA_KM_PROGRESS_METERS, snapshot.currentKmProgressMeters)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(snapshot: PaceSnapshot) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: PaceSnapshot): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val distance = String.format(Locale.US, "%.2f km", snapshot.distanceMeters / 1000.0)
        val state = if (isPaused) "Paused" else "Running"
        val content = "$state | Time ${formatDuration(currentMovingDurationMs())} | Distance $distance | Pace ${formatPace(snapshot.paceSecPerKm)}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Running pace tracking active")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running Pace",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for active GPS tracking."
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun formatPace(secPerKm: Double): String {
        if (secPerKm.isNaN() || secPerKm <= 0.0 || secPerKm.isInfinite()) return "--:-- /km"
        val sec = secPerKm.toInt()
        val min = sec / 60
        val rem = sec % 60
        return String.format(Locale.US, "%d:%02d /km", min, rem)
    }

    private fun hasFinePermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopTracking(saveRun: Boolean) {
        if (isTracking || isPaused) {
            fused.removeLocationUpdates(callback)
        }
        val endElapsed = SystemClock.elapsedRealtime()
        if (saveRun) {
            persistRunSummary(endElapsed)
        }
        isTracking = false
        isPaused = false
        pausedStartedAtElapsedMs = 0L
        publishMetrics(latest)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun persistRunSummary(endElapsedMs: Long) {
        if (runStartedAtElapsedMs <= 0L) return
        val durationMs = calculateMovingDurationMs(endElapsedMs)
        val distanceMeters = latest.distanceMeters
        val splitPaces = calculator.getCompletedSplitPacesSecPerKm()
        val splits = splitPaces.mapIndexed { index, actualPace ->
            val km = index + 1
            KmSplit(
                kilometer = km,
                targetPaceSecPerKm = targetForKilometer(km),
                actualPaceSecPerKm = actualPace
            )
        }

        val distanceKm = distanceMeters / 1000.0
        val averagePaceSecPerKm = if (distanceKm > 0.0 && durationMs > 0L) {
            (durationMs / 1000.0) / distanceKm
        } else {
            Double.NaN
        }
        val averageSpeedKmh = if (durationMs > 0L) {
            (distanceMeters / durationMs.toDouble()) * 3600.0
        } else {
            0.0
        }

        RunHistoryStore.append(
            this,
            RunRecord(
                startedAtEpochMs = runStartedAtEpochMs,
                endedAtEpochMs = System.currentTimeMillis(),
                durationMs = durationMs,
                distanceMeters = distanceMeters,
                averagePaceSecPerKm = averagePaceSecPerKm,
                averageSpeedKmh = averageSpeedKmh,
                targetPaceSec = targetPaceSec,
                splits = splits
            )
        )
    }

    private fun calculateMovingDurationMs(endElapsedMs: Long): Long {
        if (runStartedAtElapsedMs <= 0L) return 0L
        var pausedMs = pausedAccumulatedMs
        if (isPaused && pausedStartedAtElapsedMs > 0L) {
            pausedMs += (endElapsedMs - pausedStartedAtElapsedMs).coerceAtLeast(0L)
        }
        return (endElapsedMs - runStartedAtElapsedMs - pausedMs).coerceAtLeast(0L)
    }

    private fun currentMovingDurationMs(): Long {
        if (!isTracking && runStartedAtElapsedMs <= 0L) return 0L
        return calculateMovingDurationMs(SystemClock.elapsedRealtime())
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale.US
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    private enum class PaceState {
        UNKNOWN,
        ON_TARGET,
        TOO_SLOW,
        TOO_FAST
    }

    companion object {
        const val ACTION_START = "com.example.runningpace.START"
        const val ACTION_PAUSE = "com.example.runningpace.PAUSE"
        const val ACTION_RESUME = "com.example.runningpace.RESUME"
        const val ACTION_STOP = "com.example.runningpace.STOP"
        const val ACTION_UPDATE_TARGET = "com.example.runningpace.UPDATE_TARGET"
        const val ACTION_METRICS = "com.example.runningpace.METRICS"

        const val EXTRA_TARGET_PACE_SEC = "target_pace_sec"
        const val EXTRA_KM_TARGETS = "km_targets"
        const val EXTRA_DISTANCE_METERS = "distance_meters"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_PACE_SEC_PER_KM = "pace_sec_per_km"
        const val EXTRA_IS_TRACKING = "is_tracking"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_COMPLETED_KM = "completed_km"
        const val EXTRA_KM_PROGRESS_METERS = "km_progress_meters"

        const val DEFAULT_TARGET_PACE_SEC = 7.0 * 60.0 + 20.0
        const val PACE_TOLERANCE_SECONDS = 5.0

        private const val FEEDBACK_COOLDOWN_MS = 8000L
        private const val VOICE_STATE_CHANGE_COOLDOWN_MS = 3000L
        private const val VOICE_REPEAT_COOLDOWN_MS = 20000L
        private const val CHANNEL_ID = "running_pace_channel"
        private const val NOTIFICATION_ID = 7001
    }
}
