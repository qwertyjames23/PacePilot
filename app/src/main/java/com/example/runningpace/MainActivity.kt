package com.example.runningpace

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.view.Gravity
import android.view.View
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var targetInput: EditText
    private lateinit var kmTargetsContainer: LinearLayout
    private lateinit var addKmTargetButton: Button
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    private lateinit var stopButton: Button
    private lateinit var distanceText: TextView
    private lateinit var speedText: TextView
    private lateinit var paceText: TextView
    private lateinit var targetText: TextView
    private lateinit var stateText: TextView
    private lateinit var elapsedHeroValue: TextView
    private lateinit var heroPaceValue: TextView
    private lateinit var heroDistanceValue: TextView
    private lateinit var kmProgressText: TextView
    private lateinit var kmProgressRow: LinearLayout
    private lateinit var kmProgressLabel: TextView
    private lateinit var kmProgressBar: android.widget.ProgressBar
    private lateinit var splitsText: TextView
    private lateinit var historyText: TextView
    private lateinit var setupSection: LinearLayout
    private lateinit var metricsCard: LinearLayout
    private lateinit var buttonRow: LinearLayout

    private val warmupClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var warmupActive = false
    private val warmupCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { /* pre-warm only */ }
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private var lastElapsedMs = 0L
    private var lastElapsedReceivedAtMs = 0L
    private var isCurrentlyPaused = false
    private val timerTick: Runnable = object : Runnable {
        override fun run() {
            if (currentTracking && !isCurrentlyPaused) {
                val interpolated = lastElapsedMs + (SystemClock.elapsedRealtime() - lastElapsedReceivedAtMs)
                elapsedHeroValue.text = formatDuration(interpolated)
            }
            timerHandler.postDelayed(this, 1000L)
        }
    }

    private var isReceiverRegistered = false
    private var pendingTargetPaceSec: Double? = null
    private var pendingKmTargetsEncoded: String? = null
    private var currentTracking = false
    private val kmPaceInputs = linkedMapOf<Int, EditText>()
    private val kmTargetRows = linkedMapOf<Int, LinearLayout>()
    private var nextKmTarget = 2
    private val kmRowPaceSecs = linkedMapOf<Int, Int>()
    private val kmRowDisplayViews = linkedMapOf<Int, TextView>()
    private val colorPageTop = Color.parseColor("#0B111D")
    private val colorPageBottom = Color.parseColor("#05070C")
    private val colorCard = Color.parseColor("#141D2E")
    private val colorCardBorder = Color.parseColor("#253554")
    private val colorTextPrimary = Color.parseColor("#F2F7FF")
    private val colorTextSecondary = Color.parseColor("#9CB1CF")
    private val colorAccent = Color.parseColor("#00D4C8")
    private val colorWarn = Color.parseColor("#FFB347")
    private val colorDanger = Color.parseColor("#FF6B6B")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "Fine location permission is required.", Toast.LENGTH_LONG).show()
            pendingTargetPaceSec = null
            pendingKmTargetsEncoded = null
            return@registerForActivityResult
        }
        requestBackgroundPermissionIfNeeded()
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Toast.makeText(
                this,
                "Background location denied. Tracking may pause when minimized.",
                Toast.LENGTH_LONG
            ).show()
        }
        startTrackingService()
    }

    // Receive live metrics from foreground service.
    private val metricsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationService.ACTION_METRICS) return

            val distance = intent.getDoubleExtra(LocationService.EXTRA_DISTANCE_METERS, 0.0)
            val speed = intent.getDoubleExtra(LocationService.EXTRA_SPEED_KMH, 0.0)
            val pace = intent.getDoubleExtra(LocationService.EXTRA_PACE_SEC_PER_KM, Double.NaN)
            val target = intent.getDoubleExtra(
                LocationService.EXTRA_TARGET_PACE_SEC,
                LocationService.DEFAULT_TARGET_PACE_SEC
            )
            val tracking = intent.getBooleanExtra(LocationService.EXTRA_IS_TRACKING, false)
            val paused = intent.getBooleanExtra(LocationService.EXTRA_IS_PAUSED, false)
            val elapsedMs = intent.getLongExtra(LocationService.EXTRA_ELAPSED_MS, 0L)

            val completedKm = intent.getIntExtra(LocationService.EXTRA_COMPLETED_KM, 0)
            val kmProgress = intent.getDoubleExtra(LocationService.EXTRA_KM_PROGRESS_METERS, 0.0)

            val wasTracking = currentTracking
            currentTracking = tracking
            isCurrentlyPaused = paused
            lastElapsedMs = elapsedMs
            lastElapsedReceivedAtMs = SystemClock.elapsedRealtime()

            heroPaceValue.text = formatPaceValue(pace)
            heroDistanceValue.text = String.format(Locale.US, "%.2f", distance / 1000.0)
            elapsedHeroValue.text = formatDuration(elapsedMs)
            distanceText.text = "Distance: ${formatDistance(distance)}"
            speedText.text = "Speed: ${String.format(Locale.US, "%.2f km/h", speed)}"
            paceText.text = "Pace: ${formatPace(pace)}"
            targetText.text = "Target: ${formatPace(target)}"

            if (tracking && !pace.isNaN()) {
                val currentKm = completedKm + 1
                val progressMeters = kmProgress.toInt().coerceIn(0, 1000)
                kmProgressLabel.text = "KM $currentKm  •  ${progressMeters}m / 1000m"
                kmProgressBar.progress = progressMeters
                kmProgressRow.visibility = View.VISIBLE
            } else {
                kmProgressRow.visibility = View.GONE
            }
            kmProgressText.visibility = View.GONE  // always hidden now

            val (statusText, statusColor) = when {
                !tracking -> "Stopped" to colorTextSecondary
                paused -> "Paused" to colorWarn
                pace.isNaN() -> "Waiting for GPS..." to colorTextSecondary
                else -> {
                    val diff = pace - target
                    val absDiff = abs(diff).toInt()
                    when {
                        abs(diff) <= LocationService.PACE_TOLERANCE_SECONDS -> "On Target" to colorAccent
                        diff > 0 -> "Too Slow  +${absDiff}s behind" to colorDanger
                        else -> "Too Fast  -${absDiff}s ahead" to colorWarn
                    }
                }
            }
            updateStateChip(statusText, statusColor)

            updateButtons(tracking, paused)
            if (wasTracking && !tracking) {
                renderHistory()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreTargetInputs()
        renderHistory()
    }

    override fun onStart() {
        super.onStart()
        registerMetricsReceiver()
        startGpsWarmup()
        timerHandler.post(timerTick)
    }

    override fun onStop() {
        persistTargetInputs()
        super.onStop()
        unregisterMetricsReceiver()
        stopGpsWarmup()
        timerHandler.removeCallbacks(timerTick)
    }

    private fun buildUi() {
        val rootPadding = dp(20)
        val itemGap = dp(12)
        val sectionGap = dp(22)

        // ── Polished header block ─────────────────────────────────────────
        val headerDot = TextView(this).apply {
            text = "●"
            setTextColor(colorAccent)
            textSize = 28f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        val headerTitle = TextView(this).apply {
            text = "PacePilot"
            setTextColor(colorTextPrimary)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        val headerTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(headerDot)
            addView(headerTitle)
        }
        val headerSubtitle = TextView(this).apply {
            text = "Smart Running Pace Guide"
            setTextColor(colorTextSecondary)
            textSize = 12f
            letterSpacing = 0.04f
        }
        val headerBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerTitleRow)
            addView(headerSubtitle)
        }

        // Full-width state banner — hidden when stopped, colored when running/paused
        stateText = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 16f
            letterSpacing = 0.02f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(withAlpha(colorTextSecondary, 0.3f))
            }
        }

        // Setup section — target inputs, hidden during active run
        val targetCard = createCardContainer()

        // Hidden backing store for km 1 (the nudge row handles display)
        targetInput = EditText(this).apply {
            visibility = View.GONE
            setText("7:20")
        }
        kmRowPaceSecs[1] = 7 * 60 + 20

        // Build km 1 nudge row directly (not via addKmTargetField to avoid putting it in kmTargetsContainer)
        val km1Row = createPaceInputRow(1)

        val kmTargetsTitle = TextView(this).apply {
            text = "RACE PLAN  •  per km"
            setTextColor(colorTextSecondary)
            textSize = 11f
            letterSpacing = 0.10f
            setTypeface(typeface, Typeface.BOLD)
        }
        kmTargetsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addKmTargetButton = Button(this).apply {
            text = "+ Add KM"
            isAllCaps = false
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(colorAccent)
            styleButton(this, Color.parseColor("#0A1E1A"), colorAccent, borderColor = withAlpha(colorAccent, 0.4f))
            setOnClickListener { addKmTargetField(nextKmTarget) }
        }
        addKmTargetField(2)
        addKmTargetField(3)

        addWithTopMargin(targetCard, targetInput, 0)       // hidden backing store
        addWithTopMargin(targetCard, km1Row, 0)            // visible km 1 nudge row
        addWithTopMargin(targetCard, kmTargetsTitle, dp(16))
        addWithTopMargin(targetCard, kmTargetsContainer, dp(6))
        addWithTopMargin(targetCard, addKmTargetButton, dp(12))

        setupSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addWithTopMargin(setupSection, targetCard, 0)

        // Start button — full width, only visible when stopped
        startButton = Button(this).apply {
            text = "Start Run"
            styleButton(this, colorAccent, Color.parseColor("#032320"))
            setOnClickListener { onStartClicked() }
        }

        // Metrics card — hidden when stopped, shown when running/paused
        metricsCard = createCardContainer()

        // ── Time hero (full-width, top) ───────────────────────────────────
        val timeLabel = TextView(this).apply {
            text = "TIME"
            setTextColor(colorTextSecondary)
            textSize = 11f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        elapsedHeroValue = TextView(this).apply {
            text = "00:00"
            setTextColor(colorTextPrimary)
            textSize = 52f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }

        // ── Pace + Distance row ───────────────────────────────────────────
        val paceDistRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val paceCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(20)
            }
        }
        heroPaceValue = TextView(this).apply {
            text = "--:--"
            setTextColor(colorTextPrimary)
            textSize = 42f
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        paceCol.addView(TextView(this).apply { text = "Pace"; setTextColor(colorTextSecondary); textSize = 13f })
        paceCol.addView(heroPaceValue)
        paceCol.addView(TextView(this).apply { text = "/km"; setTextColor(colorTextSecondary); textSize = 13f })

        val distCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroDistanceValue = TextView(this).apply {
            text = "0.00"
            setTextColor(colorTextPrimary)
            textSize = 38f
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        distCol.addView(TextView(this).apply { text = "Distance"; setTextColor(colorTextSecondary); textSize = 13f })
        distCol.addView(heroDistanceValue)
        distCol.addView(TextView(this).apply { text = "km"; setTextColor(colorTextSecondary); textSize = 13f })

        paceDistRow.addView(paceCol)
        paceDistRow.addView(distCol)

        // ── KM progress — label + horizontal bar ─────────────────────────
        kmProgressLabel = TextView(this).apply {
            text = ""
            setTextColor(colorTextSecondary)
            textSize = 12f
            letterSpacing = 0.04f
        }
        kmProgressBar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5)
            ).apply { topMargin = dp(6) }
            progressTintList = android.content.res.ColorStateList.valueOf(colorAccent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#1A253A")
            )
        }
        kmProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(kmProgressLabel)
            addView(kmProgressBar)
        }
        // keep kmProgressText for any legacy references
        kmProgressText = TextView(this).apply { visibility = View.GONE; text = "" }

        // ── Speed + Target detail row ─────────────────────────────────────
        val speedTargetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedText = createMetricText("Speed: 0.00 km/h").also {
            it.textSize = 13f
            it.setTextColor(colorTextSecondary)
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        targetText = createMetricText("Target: 7:20 /km").also {
            it.textSize = 13f
            it.setTextColor(colorTextSecondary)
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        speedTargetRow.addView(speedText)
        speedTargetRow.addView(targetText)

        // These fields are updated by receiver but not shown (heroes above replace them)
        distanceText = createMetricText("Distance: 0.00 km")
        paceText = createMetricText("Pace: --:-- /km")

        // ── Assemble metrics card ─────────────────────────────────────────
        addWithTopMargin(metricsCard, timeLabel, 0)
        addWithTopMargin(metricsCard, elapsedHeroValue, dp(2))
        metricsCard.addView(View(this).apply {
            setBackgroundColor(withAlpha(colorCardBorder, 0.5f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(14); bottomMargin = dp(16)
        })
        addWithTopMargin(metricsCard, paceDistRow, 0)
        addWithTopMargin(metricsCard, kmProgressRow, dp(14))
        metricsCard.addView(View(this).apply {
            setBackgroundColor(withAlpha(colorCardBorder, 0.5f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(14); bottomMargin = dp(14)
        })
        addWithTopMargin(metricsCard, speedTargetRow, 0)

        // Button row: [Pause or Resume] + [Stop] — horizontal, hidden when stopped
        buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        pauseButton = Button(this).apply {
            text = "Pause"
            styleButton(this, Color.parseColor("#2A3753"), colorTextPrimary)
            setOnClickListener { sendServiceAction(LocationService.ACTION_PAUSE) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        resumeButton = Button(this).apply {
            text = "Resume"
            styleButton(this, Color.parseColor("#30517B"), colorTextPrimary)
            setOnClickListener { sendServiceAction(LocationService.ACTION_RESUME) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            styleButton(this, Color.parseColor("#6E2B3B"), colorTextPrimary)
            setOnClickListener { sendServiceAction(LocationService.ACTION_STOP) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonRow.addView(pauseButton)
        buttonRow.addView(resumeButton)
        buttonRow.addView(stopButton)

        // Splits and history (always visible)
        val splitsCard = createCardContainer()
        splitsText = TextView(this).apply {
            text = "No split data yet."
            setTextColor(colorTextPrimary)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.2f)
        }
        val historyCard = createCardContainer()
        historyText = TextView(this).apply {
            text = "No completed runs yet."
            setTextColor(colorTextPrimary)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.2f)
        }
        addWithTopMargin(splitsCard, createSectionTitle("Latest Run Splits"), 0)
        addWithTopMargin(splitsCard, splitsText, itemGap)
        addWithTopMargin(historyCard, createSectionTitle("Recent Runs"), 0)
        addWithTopMargin(historyCard, historyText, itemGap)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rootPadding, rootPadding, rootPadding, rootPadding)
            addView(headerBlock)
        }
        addWithTopMargin(root, stateText, sectionGap)
        addWithTopMargin(root, setupSection, sectionGap)
        addWithTopMargin(root, startButton, itemGap)
        addWithTopMargin(root, metricsCard, sectionGap)
        addWithTopMargin(root, buttonRow, itemGap)
        addWithTopMargin(root, splitsCard, sectionGap)
        addWithTopMargin(root, historyCard, sectionGap)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colorPageTop, colorPageBottom)
            )
            addView(root)
        }

        setContentView(scrollView)
        updateButtons(tracking = false, paused = false)
    }

    private fun onStartClicked() {
        // km 1 comes from nudge row backing input (kmPaceInputs[1]) or fallback to targetInput
        val km1Raw = kmPaceInputs[1]?.text?.toString()?.trim()
            ?: targetInput.text.toString().trim()
        val targetSec = parsePaceSeconds(km1Raw)
        if (targetSec == null) {
            Toast.makeText(this, "KM 1 pace is invalid. Use nudge buttons.", Toast.LENGTH_LONG).show()
            return
        }
        val kmTargets = parseKmTargetsFromBoxes(targetSec)
        if (kmTargets == null) {
            Toast.makeText(
                this,
                "Invalid pace in km target boxes. Use mm:ss (2:00–30:00), sample: 7:10",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val kmTargetsEncoded = encodeKmTargets(kmTargets)

        if (currentTracking) {
            targetText.text = "Target pace: ${formatPace(targetSec)}"
            sendServiceAction(
                action = LocationService.ACTION_UPDATE_TARGET,
                targetPaceSec = targetSec,
                kmTargetsEncoded = kmTargetsEncoded
            )
            Toast.makeText(this, "Target pace updated.", Toast.LENGTH_SHORT).show()
            return
        }

        pendingTargetPaceSec = targetSec
        pendingKmTargetsEncoded = kmTargetsEncoded
        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }

        // Android 13+ requires runtime notification permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }

        if (required.isNotEmpty()) {
            permissionLauncher.launch(required.toTypedArray())
        } else {
            requestBackgroundPermissionIfNeeded()
        }
    }

    private fun requestBackgroundPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            // Must be requested separately after foreground location grant.
            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            startTrackingService()
        }
    }

    private fun startTrackingService() {
        stopGpsWarmup()
        val target = pendingTargetPaceSec ?: LocationService.DEFAULT_TARGET_PACE_SEC
        targetText.text = "Target pace: ${formatPace(target)}"

        sendServiceAction(
            action = LocationService.ACTION_START,
            targetPaceSec = target,
            kmTargetsEncoded = pendingKmTargetsEncoded ?: ""
        )
        pendingTargetPaceSec = null
        pendingKmTargetsEncoded = null
    }

    private fun sendServiceAction(
        action: String,
        targetPaceSec: Double? = null,
        kmTargetsEncoded: String? = null
    ) {
        val intent = Intent(this, LocationService::class.java).apply {
            this.action = action
            targetPaceSec?.let { putExtra(LocationService.EXTRA_TARGET_PACE_SEC, it) }
            kmTargetsEncoded?.let { putExtra(LocationService.EXTRA_KM_TARGETS, it) }
        }

        if (action == LocationService.ACTION_START) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsWarmup() {
        if (warmupActive || currentTracking) return
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return

        // Immediately grab last known location for near-instant first fix
        warmupClient.lastLocation.addOnSuccessListener { /* pre-warm only */ }

        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
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

    private fun stopGpsWarmup() {
        if (!warmupActive) return
        warmupClient.removeLocationUpdates(warmupCallback)
        warmupActive = false
    }

    private fun registerMetricsReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(LocationService.ACTION_METRICS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(metricsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(metricsReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun unregisterMetricsReceiver() {
        if (!isReceiverRegistered) return
        unregisterReceiver(metricsReceiver)
        isReceiverRegistered = false
    }

    private fun updateButtons(tracking: Boolean, paused: Boolean) {
        if (!tracking) {
            setupSection.visibility = View.VISIBLE
            startButton.visibility = View.VISIBLE
            stateText.visibility = View.GONE
            metricsCard.visibility = View.GONE
            buttonRow.visibility = View.GONE
            kmProgressText.visibility = View.GONE
            if (::kmProgressRow.isInitialized) kmProgressRow.visibility = View.GONE
        } else {
            setupSection.visibility = View.GONE
            startButton.visibility = View.GONE
            stateText.visibility = View.VISIBLE
            metricsCard.visibility = View.VISIBLE
            buttonRow.visibility = View.VISIBLE
            pauseButton.visibility = if (!paused) View.VISIBLE else View.GONE
            resumeButton.visibility = if (paused) View.VISIBLE else View.GONE
        }
    }

    private fun renderHistory() {
        val records = RunHistoryStore.load(this).take(5)
        renderLatestSplits(records.firstOrNull())
        if (records.isEmpty()) {
            historyText.text = "No completed runs yet."
            return
        }

        val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        historyText.text = records.mapIndexed { index, record ->
            val startedAt = dateFormat.format(Date(record.startedAtEpochMs))
            val distance = formatDistance(record.distanceMeters)
            val duration = formatDuration(record.durationMs)
            val pace = formatPace(record.averagePaceSecPerKm)
            val avgSpeed = String.format(Locale.US, "%.2f km/h", record.averageSpeedKmh)
            "${index + 1}. $startedAt | $distance | $duration | $pace | $avgSpeed"
        }.joinToString(separator = "\n")
    }

    private fun renderLatestSplits(record: RunRecord?) {
        if (record == null || record.splits.isEmpty()) {
            splitsText.text = "No split data yet."
            return
        }

        splitsText.text = record.splits.joinToString(separator = "\n") { split ->
            val diffSec = split.actualPaceSecPerKm - split.targetPaceSecPerKm
            val diffAbsSec = abs(diffSec).toInt()
            val diffText = when {
                diffAbsSec == 0 -> "on target"
                diffSec > 0.0 -> "+${diffAbsSec}s behind"
                else -> "-${diffAbsSec}s ahead"
            }
            val hitMiss = if (abs(diffSec) <= LocationService.PACE_TOLERANCE_SECONDS) "Hit" else "Miss"

            "KM ${split.kilometer} | T ${formatPace(split.targetPaceSecPerKm)} | A ${formatPace(split.actualPaceSecPerKm)} | $diffText | $hitMiss"
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun parsePaceSeconds(value: String): Double? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val min = parts[0].toIntOrNull() ?: return null
        val sec = parts[1].toIntOrNull() ?: return null
        if (min < 0 || sec !in 0..59) return null
        val total = min * 60 + sec
        if (total < 120 || total > 1800) return null  // must be between 2:00 and 30:00 /km
        return total.toDouble()
    }

    private fun parseKmTargetsFromBoxes(firstKmTargetSec: Double): Map<Int, Double>? {
        val parsed = linkedMapOf<Int, Double>()
        parsed[1] = firstKmTargetSec

        for ((km, input) in kmPaceInputs) {
            if (km == 1) continue  // km 1 already set from firstKmTargetSec
            val raw = input.text.toString().trim()
            if (raw.isEmpty()) continue

            val paceSec = parsePaceSeconds(raw) ?: return null
            parsed[km] = paceSec
        }
        return parsed.toSortedMap()
    }

    private fun encodeKmTargets(targets: Map<Int, Double>): String {
        if (targets.isEmpty()) return ""
        return targets.entries
            .sortedBy { it.key }
            .joinToString(separator = ";") { "${it.key}:${it.value.toInt()}" }
    }

    private fun secsToPaceString(secs: Int): String {
        val m = secs / 60
        val s = secs % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun createPaceInputRow(km: Int): LinearLayout {
        val defaultSec = kmRowPaceSecs.getOrPut(km) { if (km == 1) 7 * 60 + 20 else 7 * 60 }

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
            text = secsToPaceString(kmRowPaceSecs[km] ?: defaultSec)
            setTextColor(colorTextPrimary)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        kmRowDisplayViews[km] = display

        val hiddenInput = EditText(this).apply {
            visibility = View.GONE
            setText(secsToPaceString(kmRowPaceSecs[km] ?: defaultSec))
        }

        fun nudge(deltaSec: Int) {
            val current = kmRowPaceSecs[km] ?: defaultSec
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
            setPadding(dp(14), dp(4), dp(14), dp(4))
            minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1A2537"))
                setStroke(dp(1), colorCardBorder)
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
            setOnClickListener { nudge(-5) }
        }

        val plusBtn = Button(this).apply {
            text = "+"
            textSize = 18f
            isAllCaps = false
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(colorAccent)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#0D2420"))
                setStroke(dp(1), colorAccent)
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
            setOnClickListener { nudge(+5) }
        }

        row.addView(label)
        row.addView(minusBtn)
        row.addView(display)
        row.addView(plusBtn)
        row.addView(hiddenInput)

        kmPaceInputs[km] = hiddenInput
        return row
    }

    private fun addKmTargetField(km: Int) {
        if (kmPaceInputs.containsKey(km)) return

        val outerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val paceRow = createPaceInputRow(km)
        paceRow.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        if (km > 1) {
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
            outerRow.addView(deleteButton)
        } else {
            outerRow.addView(paceRow)
        }

        kmTargetsContainer.addView(outerRow)
        kmTargetRows[km] = outerRow
        updateNextKmTarget()
    }

    private fun removeKmTargetField(km: Int) {
        val row = kmTargetRows.remove(km) ?: return
        kmTargetsContainer.removeView(row)
        kmPaceInputs.remove(km)
        kmRowPaceSecs.remove(km)
        kmRowDisplayViews.remove(km)
        updateNextKmTarget()
    }

    private fun updateNextKmTarget() {
        var candidate = 2
        while (kmPaceInputs.containsKey(candidate)) {
            candidate += 1
        }
        nextKmTarget = candidate
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(colorCard)
                setStroke(dp(1), colorCardBorder)
            }
        }
    }

    private fun createSectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(colorTextPrimary)
            textSize = 15f
            letterSpacing = 0.01f
            setTypeface(Typeface.DEFAULT_BOLD)
        }
    }

    private fun createMetricText(initialText: String): TextView {
        return TextView(this).apply {
            text = initialText
            setTextColor(colorTextPrimary)
            textSize = 14f
            setLineSpacing(0f, 1.1f)
        }
    }

    private fun styleInput(editText: EditText) {
        editText.setTextColor(colorTextPrimary)
        editText.setHintTextColor(colorTextSecondary)
        editText.textSize = 15f
        editText.setPadding(dp(12), dp(10), dp(12), dp(10))
        editText.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#0E1626"))
            setStroke(dp(1), colorCardBorder)
        }
    }

    private fun styleButton(button: Button, fillColor: Int, textColor: Int, borderColor: Int = fillColor) {
        button.setTextColor(textColor)
        button.textSize = 15f
        button.isAllCaps = false
        button.setTypeface(Typeface.DEFAULT_BOLD)
        button.setPadding(dp(8), dp(14), dp(8), dp(14))
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(fillColor)
            setStroke(dp(1), borderColor)
        }
    }

    private fun setButtonState(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1.0f else 0.45f
    }

    private fun updateStateChip(statusText: String, accentColor: Int) {
        stateText.text = statusText
        stateText.setTextColor(Color.WHITE)
        stateText.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(withAlpha(accentColor, 0.85f))
        }
    }

    private fun pillBackground(fillColor: Int, borderColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(fillColor)
            setStroke(dp(1), borderColor)
        }
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (alphaFraction.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun addWithTopMargin(parent: LinearLayout, view: android.view.View, topMarginPx: Int) {
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = topMarginPx
        }
        parent.addView(view)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun persistTargetInputs() {
        val prefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        val baseRaw = kmPaceInputs[1]?.text?.toString()?.trim()
            ?: targetInput.text.toString().trim()
        val rowsRaw = encodeKmRowsRaw()
        prefs.edit()
            .putString(KEY_BASE_TARGET_RAW, baseRaw)
            .putString(KEY_KM_ROWS_RAW, rowsRaw)
            .apply()
    }

    private fun restoreTargetInputs() {
        val prefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        val baseRaw = prefs.getString(KEY_BASE_TARGET_RAW, "").orEmpty()
        if (baseRaw.isNotBlank()) {
            targetInput.setText(baseRaw)
            parsePaceSeconds(baseRaw)?.toInt()?.let { restoredSec ->
                kmRowPaceSecs[1] = restoredSec
                kmRowDisplayViews[1]?.text = secsToPaceString(restoredSec)
                kmPaceInputs[1]?.setText(secsToPaceString(restoredSec))
            }
        }

        val kmRowsRaw = prefs.getString(KEY_KM_ROWS_RAW, "").orEmpty()
        val decodedRows = decodeKmRowsRaw(kmRowsRaw)
        if (decodedRows.isNotEmpty()) {
            kmTargetsContainer.removeAllViews()
            // Remove km 2+ from maps (km 1 stays — it lives outside kmTargetsContainer)
            val keysToRemove = kmPaceInputs.keys.filter { it >= 2 }
            keysToRemove.forEach { k ->
                kmPaceInputs.remove(k)
                kmTargetRows.remove(k)
                kmRowPaceSecs.remove(k)
            }
            nextKmTarget = 2

            for ((km, raw) in decodedRows) {
                parsePaceSeconds(raw)?.toInt()?.let { kmRowPaceSecs[km] = it }
                addKmTargetField(km)
                parsePaceSeconds(raw)?.toInt()?.let { restoredSec ->
                    kmRowPaceSecs[km] = restoredSec
                    kmRowDisplayViews[km]?.text = secsToPaceString(restoredSec)
                }
                kmPaceInputs[km]?.setText(raw)
            }
        }

        if (!kmPaceInputs.containsKey(2)) addKmTargetField(2)
        if (!kmPaceInputs.containsKey(3)) addKmTargetField(3)
        updateNextKmTarget()
    }

    private fun encodeKmRowsRaw(): String {
        return kmPaceInputs.entries
            .filter { it.key >= 2 }  // km 1 is saved separately as baseRaw
            .sortedBy { it.key }
            .joinToString(separator = "\n") { (km, input) ->
                val raw = input.text.toString().trim()
                "$km|$raw"
            }
    }

    private fun decodeKmRowsRaw(raw: String): List<Pair<Int, String>> {
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val km = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                if (km < 2) return@mapNotNull null
                km to parts[1].trim()
            }
            .toList()
            .sortedBy { it.first }
    }

    private fun formatDistance(meters: Double): String {
        return String.format(Locale.US, "%.2f km", meters / 1000.0)
    }

    private fun formatPace(secPerKm: Double): String {
        if (secPerKm.isNaN() || secPerKm <= 0.0 || secPerKm.isInfinite()) return "--:-- /km"
        val total = secPerKm.toInt()
        val min = total / 60
        val sec = total % 60
        return String.format(Locale.US, "%d:%02d /km", min, sec)
    }

    private fun formatPaceValue(secPerKm: Double): String {
        if (secPerKm.isNaN() || secPerKm <= 0.0 || secPerKm.isInfinite()) return "--:--"
        val total = secPerKm.toInt()
        val min = total / 60
        val sec = total % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
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

    companion object {
        private const val UI_PREFS = "running_ui"
        private const val KEY_BASE_TARGET_RAW = "base_target_raw"
        private const val KEY_KM_ROWS_RAW = "km_rows_raw"
    }
}
