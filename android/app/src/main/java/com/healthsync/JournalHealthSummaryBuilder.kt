package com.healthsync

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

/**
 * Builds a compact, journal-oriented view from the rolling raw cache.
 * Raw records remain the audit source; this summary is optimized for later
 * ChatGPT/Notion ingestion and human inspection.
 */
object JournalHealthSummaryBuilder {
    private const val OHEALTH = "com.heytap.health.international"
    private const val OHEALTH_ALT = "com.heytap.health"
    private const val GOOGLE_FIT = "com.google.android.apps.fitness"
    private const val BODY_DIARY = "com.selantoapps.bodydiary"

    fun build(snapshot: HealthSnapshot): Map<String, Any?> {
        val zone = ZoneId.systemDefault()
        val now = runCatching { Instant.parse(snapshot.exportEnd) }.getOrElse { Instant.now() }
        val today = now.atZone(zone).toLocalDate()

        val activity = linkedMapOf<String, Any?>(
            "steps" to snapshot.steps,
            "calories_active_kcal" to snapshot.caloriesActive,
            "calories_total_kcal" to snapshot.caloriesTotal,
            "distance_km" to snapshot.distanceMeters?.let { round2(it / 1000.0) },
            "exercise_minutes" to snapshot.activeMinutes,
            "elevation_gain_m" to sumToday(snapshot, "ElevationGainedRecord", today, zone, listOf(OHEALTH, OHEALTH_ALT)) {
                nestedNumber(it, "elevation", "meters")
            },
            "floors_climbed" to sumToday(snapshot, "FloorsClimbedRecord", today, zone, listOf(OHEALTH, OHEALTH_ALT)) {
                number(it["floors"])
            },
            "sources" to snapshot.summarySources,
        ).withoutNullValues()

        val heartRate = heartRateSummary(snapshot, today, zone)
        val oxygen = scalarSeriesSummary(
            snapshot, "OxygenSaturationRecord", today, zone,
            listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        ) { nestedNumber(it, "percentage", "value") }
        val respiratory = scalarSeriesSummary(
            snapshot, "RespiratoryRateRecord", today, zone,
            listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        ) { number(it["rate"]) }
        val restingHr = scalarSeriesSummary(
            snapshot, "RestingHeartRateRecord", today, zone,
            listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        ) { number(it["beatsPerMinute"]) }

        val body = linkedMapOf<String, Any?>(
            "weight" to weightSummary(snapshot, zone),
            "blood_pressure" to bloodPressureSummary(snapshot, zone),
            "body_fat" to latestScalar(
                snapshot, "BodyFatRecord", zone,
                listOf(BODY_DIARY, OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
            ) { nestedNumber(it, "percentage", "value") },
            "body_water_mass_kg" to latestScalar(
                snapshot, "BodyWaterMassRecord", zone,
                listOf(BODY_DIARY, OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
            ) { massKg(it) },
            "bone_mass_kg" to latestScalar(
                snapshot, "BoneMassRecord", zone,
                listOf(BODY_DIARY, OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
            ) { massKg(it) },
            "lean_body_mass_kg" to latestScalar(
                snapshot, "LeanBodyMassRecord", zone,
                listOf(BODY_DIARY, OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
            ) { massKg(it) },
            "height_m" to latestScalar(
                snapshot, "HeightRecord", zone,
                listOf(BODY_DIARY, OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
            ) { nestedNumber(it, "height", "meters") },
        ).withoutNullValues()

        val vitals = linkedMapOf<String, Any?>(
            "heart_rate" to heartRate,
            "resting_heart_rate" to restingHr,
            "oxygen_saturation_percent" to oxygen,
            "respiratory_rate_per_min" to respiratory,
            "hrv_rmssd" to hrvSummary(snapshot),
        ).withoutNullValues()

        return linkedMapOf(
            "schema_version" to 2,
            "generated_at" to snapshot.recordedAt,
            "activity_today" to activity,
            "vitals" to vitals,
            "sleep" to sleepSummary(snapshot, today, zone),
            "body" to body,
            "exercise_sessions_today" to exerciseSessions(snapshot, today, zone),
            "rolling_daily_summaries" to rollingDailySummaries(snapshot, zone),
            "rolling_window_days" to 7,
            "source_policy" to mapOf(
                "wearable_health" to "OHealth first, then Google Fit/other fallback",
                "weight_body_composition" to "Body Diary first until smart-scale source is configured",
                "blood_pressure" to "Google Fit first when a reading reaches Health Connect",
            ),
        )
    }

    fun latestWeightKg(snapshot: HealthSnapshot): Double? {
        val records = preferredRecords(
            snapshot.rawRecords["WeightRecord"].orEmpty(),
            listOf(BODY_DIARY, OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        )
        return records.maxByOrNull { recordInstant(it) ?: Instant.MIN }
            ?.let { nestedNumber(it, "weight", "kilograms") }
    }

    private fun weightSummary(snapshot: HealthSnapshot, zone: ZoneId): Map<String, Any?>? {
        val records = preferredRecords(
            snapshot.rawRecords["WeightRecord"].orEmpty(),
            listOf(BODY_DIARY, OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        ).sortedBy { recordInstant(it) ?: Instant.MIN }
        if (records.isEmpty()) return null

        val readings = records.mapNotNull { record ->
            val kg = nestedNumber(record, "weight", "kilograms") ?: return@mapNotNull null
            val instant = recordInstant(record) ?: return@mapNotNull null
            mapOf(
                "kg" to round1(kg),
                "at_local" to instant.atZone(zone).toString(),
                "at_utc" to instant.toString(),
                "source" to source(record),
            )
        }
        if (readings.isEmpty()) return null

        val firstKg = number(readings.first()["kg"])
        val latestKg = number(readings.last()["kg"])
        return linkedMapOf(
            "latest_kg" to latestKg,
            "latest_at_local" to readings.last()["at_local"],
            "latest_at_utc" to readings.last()["at_utc"],
            "source" to readings.last()["source"],
            "readings_7d" to readings,
            "change_7d_kg" to if (firstKg != null && latestKg != null) round1(latestKg - firstKg) else null,
            "reading_count_7d" to readings.size,
        ).withoutNullValues()
    }

    private fun heartRateSummary(
        snapshot: HealthSnapshot,
        today: java.time.LocalDate,
        zone: ZoneId
    ): Map<String, Any?>? {
        val records = preferredRecords(
            snapshot.rawRecords["HeartRateRecord"].orEmpty(),
            listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        )
        if (records.isEmpty() && snapshot.heartRateAvg == null) return null

        val samples = mutableListOf<Pair<Instant, Double>>()
        records.forEach { record ->
            val list = record["samples"] as? List<*> ?: return@forEach
            list.forEach { item ->
                val sample = item as? Map<*, *> ?: return@forEach
                val bpm = number(sample["beatsPerMinute"]) ?: return@forEach
                val time = (sample["time"] as? String)?.let { parseInstant(it) } ?: return@forEach
                samples += time to bpm
            }
        }

        val todaySamples = samples.filter { it.first.atZone(zone).toLocalDate() == today }
        val latest = samples.maxByOrNull { it.first }
        return linkedMapOf(
            "source" to records.firstOrNull()?.let(::source),
            "aggregate_today_avg_bpm" to snapshot.heartRateAvg,
            "today" to stats(todaySamples, zone, "bpm"),
            "latest" to latest?.let {
                mapOf(
                    "bpm" to round1(it.second),
                    "at_local" to it.first.atZone(zone).toString(),
                    "at_utc" to it.first.toString(),
                )
            },
        ).withoutNullValues()
    }

    private fun scalarSeriesSummary(
        snapshot: HealthSnapshot,
        recordType: String,
        today: java.time.LocalDate,
        zone: ZoneId,
        priority: List<String>,
        value: (Map<String, Any?>) -> Double?
    ): Map<String, Any?>? {
        val records = preferredRecords(snapshot.rawRecords[recordType].orEmpty(), priority)
        if (records.isEmpty()) return null
        val values = records.mapNotNull { record ->
            val instant = recordInstant(record) ?: return@mapNotNull null
            val metric = value(record) ?: return@mapNotNull null
            instant to metric
        }
        if (values.isEmpty()) return null

        val todayValues = values.filter { it.first.atZone(zone).toLocalDate() == today }
        val latest = values.maxByOrNull { it.first }
        return linkedMapOf(
            "source" to records.firstOrNull()?.let(::source),
            "today" to stats(todayValues, zone, "value"),
            "latest" to latest?.let {
                mapOf(
                    "value" to round1(it.second),
                    "at_local" to it.first.atZone(zone).toString(),
                    "at_utc" to it.first.toString(),
                )
            },
            "sample_count_7d" to values.size,
        ).withoutNullValues()
    }

    private fun stats(
        values: List<Pair<Instant, Double>>,
        zone: ZoneId,
        valueKey: String
    ): Map<String, Any?>? {
        if (values.isEmpty()) return null
        val ordered = values.sortedBy { it.first }
        val numbers = ordered.map { it.second }
        val latest = ordered.last()
        return mapOf(
            "sample_count" to numbers.size,
            "average_$valueKey" to round1(numbers.average()),
            "min_$valueKey" to round1(numbers.minOrNull() ?: return null),
            "max_$valueKey" to round1(numbers.maxOrNull() ?: return null),
            "latest_$valueKey" to round1(latest.second),
            "latest_at_local" to latest.first.atZone(zone).toString(),
            "latest_at_utc" to latest.first.toString(),
        )
    }

    private fun bloodPressureSummary(snapshot: HealthSnapshot, zone: ZoneId): Map<String, Any?>? {
        val records = preferredRecords(
            snapshot.rawRecords["BloodPressureRecord"].orEmpty(),
            listOf(GOOGLE_FIT, OHEALTH, OHEALTH_ALT)
        ).sortedBy { recordInstant(it) ?: Instant.MIN }
        if (records.isEmpty()) return null

        val readings = records.mapNotNull { record ->
            val instant = recordInstant(record) ?: return@mapNotNull null
            val systolic = pressure(record["systolic"]) ?: return@mapNotNull null
            val diastolic = pressure(record["diastolic"]) ?: return@mapNotNull null
            linkedMapOf(
                "systolic_mmhg" to round1(systolic),
                "diastolic_mmhg" to round1(diastolic),
                "at_local" to instant.atZone(zone).toString(),
                "at_utc" to instant.toString(),
                "source" to source(record),
            )
        }
        if (readings.isEmpty()) return null
        return mapOf(
            "latest" to readings.last(),
            "readings_7d" to readings,
            "reading_count_7d" to readings.size,
        )
    }

    private fun rollingDailySummaries(
        snapshot: HealthSnapshot,
        zone: ZoneId
    ): List<Map<String, Any?>> {
        return snapshot.rollingDailySummaries.mapNotNull { daily ->
            val date = runCatching { LocalDate.parse(daily.date) }.getOrNull()
                ?: return@mapNotNull null
            val sleepSessions = sleepSessions(snapshot, date, zone)
            val exercises = exerciseSessions(snapshot, date, zone)

            linkedMapOf<String, Any?>(
                "date" to daily.date,
                "steps" to daily.steps,
                "calories_active_kcal" to daily.caloriesActive,
                "calories_total_kcal" to daily.caloriesTotal,
                "distance_km" to daily.distanceMeters?.let { round2(it / 1000.0) },
                "exercise_minutes" to daily.exerciseMinutes,
                "heart_rate_sample_avg_bpm" to daily.heartRateAvg,
                "heart_rate_resting_bpm" to daily.heartRateResting,
                "sleep" to if (daily.sleepDurationMinutes != null || sleepSessions.isNotEmpty()) {
                    linkedMapOf<String, Any?>(
                        "total_minutes" to daily.sleepDurationMinutes,
                        "session_count" to sleepSessions.size,
                        "sessions" to sleepSessions,
                        "source" to daily.sources["sleep_duration_minutes"],
                        "duration_source" to if (daily.sleepDurationMinutes != null) {
                            "Health Connect aggregate"
                        } else {
                            null
                        },
                        "session_boundaries_source" to if (sleepSessions.isNotEmpty()) {
                            "raw SleepSessionRecord"
                        } else {
                            null
                        },
                    ).withoutNullValues()
                } else {
                    null
                },
                "exercise_sessions" to exercises.takeIf { it.isNotEmpty() },
                "sources" to daily.sources,
            ).withoutNullValues()
        }
    }

    private fun sleepSessions(
        snapshot: HealthSnapshot,
        date: LocalDate,
        zone: ZoneId
    ): List<Map<String, Any?>> {
        val candidates = snapshot.rawRecords["SleepSessionRecord"].orEmpty()
            .filter { record ->
                val end = parseInstant(record["endTime"] as? String) ?: return@filter false
                end.atZone(zone).toLocalDate() == date
            }
        val records = preferredRecords(
            candidates,
            listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        )

        return records.mapNotNull { record ->
            val start = parseInstant(record["startTime"] as? String) ?: return@mapNotNull null
            val end = parseInstant(record["endTime"] as? String) ?: return@mapNotNull null
            linkedMapOf(
                "start_local" to start.atZone(zone).toString(),
                "end_local" to end.atZone(zone).toString(),
                "start_utc" to start.toString(),
                "end_utc" to end.toString(),
                "session_window_minutes" to Duration.between(start, end).toMinutes(),
                "source" to source(record),
                "stages" to record["stages"],
            )
        }.sortedBy { it["start_local"]?.toString() }
    }

    private fun sleepSummary(
        snapshot: HealthSnapshot,
        today: java.time.LocalDate,
        zone: ZoneId
    ): Map<String, Any?>? {
        val sessions = sleepSessions(snapshot, today, zone)
        if (sessions.isEmpty() && snapshot.sleepDurationMinutes == null) return null

        val summedWindowMinutes = sessions.sumOf {
            (it["session_window_minutes"] as? Number)?.toLong() ?: 0L
        }
        return linkedMapOf(
            "total_minutes" to (snapshot.sleepDurationMinutes ?: summedWindowMinutes),
            "session_count" to sessions.size,
            "sessions" to sessions,
            "first_start_local" to sessions.firstOrNull()?.get("start_local"),
            "last_end_local" to sessions.lastOrNull()?.get("end_local"),
            "source" to snapshot.summarySources["sleep_duration_minutes"]
                ?: sessions.firstOrNull()?.get("source"),
            "duration_source" to if (snapshot.sleepDurationMinutes != null) {
                "Health Connect aggregate"
            } else {
                "summed session windows fallback"
            },
        ).withoutNullValues()
    }

    private fun exerciseSessions(
        snapshot: HealthSnapshot,
        today: java.time.LocalDate,
        zone: ZoneId
    ): List<Map<String, Any?>> {
        val candidates = snapshot.rawRecords["ExerciseSessionRecord"].orEmpty()
            .filter { record ->
                val end = parseInstant(record["endTime"] as? String) ?: return@filter false
                end.atZone(zone).toLocalDate() == today
            }
        val records = preferredRecords(
            candidates,
            listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        )
        return records.mapNotNull { record ->
            val start = parseInstant(record["startTime"] as? String) ?: return@mapNotNull null
            val end = parseInstant(record["endTime"] as? String) ?: return@mapNotNull null
            linkedMapOf<String, Any?>(
                "exercise_type" to record["exerciseType"],
                "title" to record["title"],
                "start_local" to start.atZone(zone).toString(),
                "end_local" to end.atZone(zone).toString(),
                "duration_minutes" to Duration.between(start, end).toMinutes(),
                "source" to source(record),
            ).withoutNullValues()
        }.sortedBy { it["start_local"]?.toString() }
    }

    private fun hrvSummary(snapshot: HealthSnapshot): Map<String, Any?>? {
        if (snapshot.hrvRmssdSampleCount <= 0) return null
        return linkedMapOf(
            "sample_count" to snapshot.hrvRmssdSampleCount,
            "average_ms" to snapshot.hrvRmssdAvgMs,
            "median_ms" to snapshot.hrvRmssdMedianMs,
            "min_ms" to snapshot.hrvRmssdMinMs,
            "max_ms" to snapshot.hrvRmssdMaxMs,
        ).withoutNullValues()
    }

    private fun latestScalar(
        snapshot: HealthSnapshot,
        recordType: String,
        zone: ZoneId,
        priority: List<String>,
        value: (Map<String, Any?>) -> Double?
    ): Map<String, Any?>? {
        val record = preferredRecords(snapshot.rawRecords[recordType].orEmpty(), priority)
            .maxByOrNull { recordInstant(it) ?: Instant.MIN } ?: return null
        val metric = value(record) ?: return null
        val instant = recordInstant(record) ?: return null
        return mapOf(
            "value" to round2(metric),
            "at_local" to instant.atZone(zone).toString(),
            "at_utc" to instant.toString(),
            "source" to source(record),
        )
    }

    private fun sumToday(
        snapshot: HealthSnapshot,
        recordType: String,
        today: java.time.LocalDate,
        zone: ZoneId,
        priority: List<String>,
        value: (Map<String, Any?>) -> Double?
    ): Double? {
        val candidates = snapshot.rawRecords[recordType].orEmpty()
            .filter { recordInstant(it)?.atZone(zone)?.toLocalDate() == today }
        val records = preferredRecords(candidates, priority)
        val values = records.mapNotNull(value)
        if (values.isEmpty()) return null
        return round2(values.sum())
    }

    private fun preferredRecords(
        records: List<Map<String, Any?>>,
        priority: List<String>
    ): List<Map<String, Any?>> {
        if (records.isEmpty()) return emptyList()
        priority.forEach { packageName ->
            val matching = records.filter { source(it) == packageName }
            if (matching.isNotEmpty()) return matching
        }
        return records
    }

    private fun source(record: Map<String, Any?>): String? {
        val metadata = record["metadata"] as? Map<*, *> ?: return null
        val origin = metadata["dataOrigin"] as? Map<*, *> ?: return null
        return origin["packageName"] as? String
    }

    private fun recordInstant(record: Map<String, Any?>): Instant? {
        for (key in listOf("endTime", "time", "startTime")) {
            val parsed = parseInstant(record[key] as? String)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseInstant(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun nestedNumber(record: Map<String, Any?>, parent: String, child: String): Double? {
        val nested = record[parent] as? Map<*, *> ?: return null
        return number(nested[child])
    }

    private fun massKg(record: Map<String, Any?>): Double? {
        for (key in listOf("mass", "weight", "bodyWaterMass", "boneMass", "leanBodyMass")) {
            val nested = record[key] as? Map<*, *> ?: continue
            number(nested["kilograms"])?.let { return it }
        }
        return null
    }

    private fun pressure(value: Any?): Double? {
        if (value is Number) return value.toDouble()
        val map = value as? Map<*, *> ?: return null
        for (key in listOf("millimetersOfMercury", "millimetresOfMercury", "mmHg", "value")) {
            number(map[key])?.let { return it }
        }
        return null
    }

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    private fun <K, V> Map<K, V?>.withoutNullValues(): Map<K, V> {
        val output = linkedMapOf<K, V>()
        entries.forEach { entry ->
            val value = entry.value
            if (value != null) output[entry.key] = value
        }
        return output
    }
}
