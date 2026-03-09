package com.example.runningpace

import android.location.Location
import kotlin.math.max
import kotlin.math.min

// Snapshot model used by UI + service notification updates.
data class PaceSnapshot(
    val distanceMeters: Double,
    val speedKmh: Double,
    val paceSecPerKm: Double,
    val completedKilometers: Int,
    val currentKmProgressMeters: Double = 0.0
)

class PaceCalculator {

    private var totalDistanceMeters = 0.0
    private var previousLocation: Location? = null
    private var recentSpeedMps = 0.0
    private var completedKilometers = 0
    private var currentKmDistanceMeters = 0.0
    private var currentKmDurationSec = 0.0
    private var lastCompletedKmPaceSec = Double.NaN
    private val completedSplitPacesSecPerKm = mutableListOf<Double>()

    // Reset calculator at the beginning of a run.
    fun reset() {
        totalDistanceMeters = 0.0
        previousLocation = null
        recentSpeedMps = 0.0
        completedKilometers = 0
        currentKmDistanceMeters = 0.0
        currentKmDurationSec = 0.0
        lastCompletedKmPaceSec = Double.NaN
        completedSplitPacesSecPerKm.clear()
    }

    // Update distance/speed/pace with each new GPS point.
    fun onLocation(location: Location): PaceSnapshot {
        val last = previousLocation
        var speedMps = 0.0

        if (last != null) {
            val segmentMeters = last.distanceTo(location).toDouble()
            val deltaSec = ((location.time - last.time).coerceAtLeast(1L)) / 1000.0
            val estimatedSegmentSpeedMps = segmentMeters / deltaSec
            val effectiveAccuracyMeters = if (location.hasAccuracy() && last.hasAccuracy()) {
                max(location.accuracy.toDouble(), last.accuracy.toDouble())
            } else {
                DEFAULT_ASSUMED_ACCURACY_METERS
            }
            val minDistanceToCount = max(
                MIN_DISTANCE_THRESHOLD_METERS,
                effectiveAccuracyMeters * ACCURACY_TO_DISTANCE_FACTOR
            )
            val shouldCountSegment =
                segmentMeters >= minDistanceToCount &&
                    segmentMeters <= MAX_SEGMENT_DISTANCE_METERS &&
                    estimatedSegmentSpeedMps <= MAX_REASONABLE_SPEED_MPS

            if (shouldCountSegment) {
                accumulateAcceptedSegment(segmentMeters, deltaSec)
                speedMps = estimatedSegmentSpeedMps
            }
        }

        previousLocation = location

        // Smooth short spikes so pace feedback is less jumpy.
        recentSpeedMps = if (speedMps == 0.0) {
            recentSpeedMps * SPEED_DECAY_WHEN_STOPPED
        } else if (recentSpeedMps == 0.0) {
            speedMps
        } else {
            recentSpeedMps * SPEED_SMOOTHING_KEEP + speedMps * (1.0 - SPEED_SMOOTHING_KEEP)
        }

        val speedKmh = recentSpeedMps * 3.6
        val paceSecPerKm = currentLapPaceSecPerKm()

        return PaceSnapshot(
            distanceMeters = totalDistanceMeters,
            speedKmh = speedKmh,
            paceSecPerKm = paceSecPerKm,
            completedKilometers = completedKilometers,
            currentKmProgressMeters = currentKmDistanceMeters
        )
    }

    private fun accumulateAcceptedSegment(segmentMeters: Double, segmentSec: Double) {
        if (segmentMeters <= 0.0 || segmentSec <= 0.0) return

        var remainingMeters = segmentMeters
        var remainingSec = segmentSec

        while (remainingMeters > EPSILON_METERS) {
            val metersToBoundary = (1000.0 - currentKmDistanceMeters).coerceAtLeast(0.0)
            val chunkMeters = min(remainingMeters, metersToBoundary)
            if (chunkMeters <= EPSILON_METERS) break

            val chunkSec = remainingSec * (chunkMeters / remainingMeters)

            totalDistanceMeters += chunkMeters
            currentKmDistanceMeters += chunkMeters
            currentKmDurationSec += chunkSec

            remainingMeters -= chunkMeters
            remainingSec = (remainingSec - chunkSec).coerceAtLeast(0.0)

            if (currentKmDistanceMeters + EPSILON_METERS >= 1000.0) {
                lastCompletedKmPaceSec = if (currentKmDurationSec > 0.0) {
                    currentKmDurationSec / (currentKmDistanceMeters / 1000.0)
                } else {
                    Double.NaN
                }
                completedKilometers += 1
                completedSplitPacesSecPerKm += lastCompletedKmPaceSec
                currentKmDistanceMeters = 0.0
                currentKmDurationSec = 0.0
            }
        }
    }

    private fun currentLapPaceSecPerKm(): Double {
        if (currentKmDistanceMeters >= MIN_DISTANCE_FOR_CURRENT_KM_PACE_METERS &&
            currentKmDurationSec > 0.0
        ) {
            return currentKmDurationSec / (currentKmDistanceMeters / 1000.0)
        }

        if (!lastCompletedKmPaceSec.isNaN()) {
            return lastCompletedKmPaceSec
        }

        return Double.NaN
    }

    fun getCompletedSplitPacesSecPerKm(): List<Double> {
        return completedSplitPacesSecPerKm.toList()
    }

    companion object {
        private const val MIN_DISTANCE_THRESHOLD_METERS = 3.0
        private const val MAX_SEGMENT_DISTANCE_METERS = 60.0
        private const val MAX_REASONABLE_SPEED_MPS = 8.5
        private const val DEFAULT_ASSUMED_ACCURACY_METERS = 15.0
        private const val ACCURACY_TO_DISTANCE_FACTOR = 0.75
        private const val SPEED_SMOOTHING_KEEP = 0.7
        private const val SPEED_DECAY_WHEN_STOPPED = 0.35
        private const val MIN_DISTANCE_FOR_CURRENT_KM_PACE_METERS = 120.0
        private const val EPSILON_METERS = 1e-3
    }
}
