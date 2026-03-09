package com.example.runningpace

import android.content.Context

data class RunRecord(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val averagePaceSecPerKm: Double,
    val averageSpeedKmh: Double,
    val targetPaceSec: Double,
    val splits: List<KmSplit> = emptyList()
)

data class KmSplit(
    val kilometer: Int,
    val targetPaceSecPerKm: Double,
    val actualPaceSecPerKm: Double
)

object RunHistoryStore {

    private const val PREFS_NAME = "running_history"
    private const val KEY_RECORDS = "records_v1"
    private const val MAX_RECORDS = 50

    fun load(context: Context): List<RunRecord> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, "")
            .orEmpty()
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .mapNotNull(::decode)
            .toList()
    }

    fun append(context: Context, record: RunRecord) {
        val updated = listOf(record) + load(context)
        val trimmed = if (updated.size > MAX_RECORDS) {
            updated.take(MAX_RECORDS)
        } else {
            updated
        }
        val encoded = trimmed.joinToString(separator = "\n", transform = ::encode)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, encoded)
            .apply()
    }

    private fun encode(record: RunRecord): String {
        return listOf(
            record.startedAtEpochMs,
            record.endedAtEpochMs,
            record.durationMs,
            record.distanceMeters,
            record.averagePaceSecPerKm,
            record.averageSpeedKmh,
            record.targetPaceSec,
            encodeSplits(record.splits)
        ).joinToString("|")
    }

    private fun decode(line: String): RunRecord? {
        val parts = line.split('|')
        if (parts.size < 7) return null
        val startedAtEpochMs = parts[0].toLongOrNull() ?: return null
        val endedAtEpochMs = parts[1].toLongOrNull() ?: return null
        val durationMs = parts[2].toLongOrNull() ?: return null
        val distanceMeters = parts[3].toDoubleOrNull() ?: return null
        val averagePaceSecPerKm = parts[4].toDoubleOrNull() ?: return null
        val averageSpeedKmh = parts[5].toDoubleOrNull() ?: return null
        val targetPaceSec = parts[6].toDoubleOrNull() ?: return null
        val splits = if (parts.size >= 8) decodeSplits(parts[7]) else emptyList()

        return RunRecord(
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = endedAtEpochMs,
            durationMs = durationMs,
            distanceMeters = distanceMeters,
            averagePaceSecPerKm = averagePaceSecPerKm,
            averageSpeedKmh = averageSpeedKmh,
            targetPaceSec = targetPaceSec,
            splits = splits
        )
    }

    private fun encodeSplits(splits: List<KmSplit>): String {
        if (splits.isEmpty()) return ""
        return splits.joinToString(";") {
            "${it.kilometer},${it.targetPaceSecPerKm},${it.actualPaceSecPerKm}"
        }
    }

    private fun decodeSplits(raw: String): List<KmSplit> {
        if (raw.isBlank()) return emptyList()

        return raw.split(';')
            .mapNotNull { row ->
                val parts = row.split(',', limit = 3)
                if (parts.size != 3) return@mapNotNull null

                val km = parts[0].toIntOrNull() ?: return@mapNotNull null
                val target = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val actual = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                if (km <= 0) return@mapNotNull null

                KmSplit(
                    kilometer = km,
                    targetPaceSecPerKm = target,
                    actualPaceSecPerKm = actual
                )
            }
    }
}
