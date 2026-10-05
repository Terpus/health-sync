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

    private data class SleepWindow(
        val start: Instant,
        val end: Instant,
        val source: String?,
        val stages: Any?,
        val mergedRawSessionCount: Int = 1,
    )

    private data class NormalizedSleepSessions(
        val sessions: List<Map<String, Any?>>,
        val rawSessionCount: Int,
        val unionMinutes: Long,
    )

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
            "exercise_minutes_semantics" to snapshot.activeMinutes?.let {
                "Health Connect aggregate"
            },
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

        val exerciseSessionsToday = exerciseSessions(snapshot, today, zone)
        val exerciseToday = exerciseSummary(
            aggregateMinutes = snapshot.activeMinutes,
            aggregateSource = snapshot.summarySources["exercise_session_minutes"],
            sessions = exerciseSessionsToday,
        )
        val sleepToday = sleepSummary(snapshot, today, zone)

        return linkedMapOf(
            "schema_version" to 4,
            "generated_at" to snapshot.recordedAt,
            "activity_today" to activity,
            "vitals" to vitals,
            "sleep" to sleepToday,
            "body" to body,
            "exercise_today" to exerciseToday,
            "exercise_sessions_today" to exerciseSessionsToday,
            "rolling_daily_summaries" to rollingDailySummaries(snapshot, zone),
            "rolling_window_days" to 7,
            "data_quality" to dataQuality(snapshot, today, zone, heartRate, sleepToday, exerciseToday),
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
        val records = snapshot.rawRecords["HeartRateRecord"].orEmpty()
        if (records.isEmpty() && snapshot.heartRateAvg == null) return null

        val samples = mutableListOf<Triple<Instant, Double, String?>>()
        records.forEach recordLoop@ { record ->
            val recordSource = source(record)
            val list = record["samples"] as? List<*> ?: return@recordLoop
            list.forEach sampleLoop@ { item ->
                val sample = item as? Map<*, *> ?: return@sampleLoop
                val bpm = number(sample["beatsPerMinute"]) ?: return@sampleLoop
                val time = (sample["time"] as? String)?.let { parseInstant(it) }
                    ?: return@sampleLoop
                samples += Triple(time, bpm, recordSource)
            }
        }

        val todayCandidates = samples.filter { it.first.atZone(zone).toLocalDate() == today }
        val priority = listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        val todaySource = preferredSource(todayCandidates.map { it.third }, priority)
        val todaySelected = if (todaySource != null) {
            todayCandidates.filter { it.third == todaySource }
        } else {
            todayCandidates
        }
        val latest = samples.maxByOrNull { it.first }

        return linkedMapOf(
            "source" to (todaySource ?: latest?.third),
            "aggregate_source" to snapshot.summarySources["heart_rate_sample_avg_bpm"],
            "aggregate_today_avg_bpm" to snapshot.heartRateAvg,
            "today_source" to todaySource,
            "today" to stats(
                todaySelected.map { it.first to it.second },
                zone,
                "bpm"
            ),
            "latest" to latest?.let {
                mapOf(
                    "bpm" to round1(it.second),
                    "at_local" to it.first.atZone(zone).toString(),
                    "at_utc" to it.first.toString(),
                    "source" to it.third,
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
        val records = snapshot.rawRecords[recordType].orEmpty()
        if (records.isEmpty()) return null

        val values = records.mapNotNull { record ->
            val instant = recordInstant(record) ?: return@mapNotNull null
            val metric = value(record) ?: return@mapNotNull null
            Triple(instant, metric, source(record))
        }
        if (values.isEmpty()) return null

        val todayCandidates = values.filter { it.first.atZone(zone).toLocalDate() == today }
        val todaySource = preferredSource(todayCandidates.map { it.third }, priority)
        val todaySelected = if (todaySource != null) {
            todayCandidates.filter { it.third == todaySource }
        } else {
            todayCandidates
        }
        val latest = values.maxByOrNull { it.first }

        return linkedMapOf(
            "source" to (todaySource ?: latest?.third),
            "today_source" to todaySource,
            "today" to stats(
                todaySelected.map { it.first to it.second },
                zone,
                "value"
            ),
            "latest" to latest?.let {
                mapOf(
                    "value" to round1(it.second),
                    "at_local" to it.first.atZone(zone).toString(),
                    "at_utc" to it.first.toString(),
                    "source" to it.third,
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
            val sleep = normalizedSleepSessions(snapshot, date, zone)
            val exercises = exerciseSessions(snapshot, date, zone)
            val exercise = exerciseSummary(
                aggregateMinutes = daily.exerciseMinutes,
                aggregateSource = daily.sources["exercise_session_minutes"],
                sessions = exercises,
            )
            val aggregateMinutes = daily.sleepDurationMinutes

            linkedMapOf<String, Any?>(
                "date" to daily.date,
                "date_basis" to "local_calendar_day",
                "steps" to daily.steps,
                "calories_active_kcal" to daily.caloriesActive,
                "calories_total_kcal" to daily.caloriesTotal,
                "distance_km" to daily.distanceMeters?.let { round2(it / 1000.0) },
                "exercise_minutes" to daily.exerciseMinutes,
                "exercise" to exercise,
                "heart_rate_sample_avg_bpm" to daily.heartRateAvg,
                "heart_rate_resting_bpm" to daily.heartRateResting,
                "sleep" to if (aggregateMinutes != null || sleep.sessions.isNotEmpty()) {
                    linkedMapOf<String, Any?>(
                        "health_connect_aggregate_minutes" to aggregateMinutes,
                        "session_window_union_minutes" to sleep.unionMinutes.takeIf {
                            sleep.sessions.isNotEmpty()
                        },
                        "aggregate_minus_session_union_minutes" to if (
                            aggregateMinutes != null && sleep.sessions.isNotEmpty()
                        ) {
                            aggregateMinutes - sleep.unionMinutes
                        } else {
                            null
                        },
                        "session_count" to sleep.sessions.size,
                        "raw_session_count_before_dedup" to sleep.rawSessionCount,
                        "sessions" to sleep.sessions,
                        "aggregate_source" to daily.sources["sleep_duration_minutes"],
                        "session_boundaries_source" to if (sleep.sessions.isNotEmpty()) {
                            "raw SleepSessionRecord, normalized by interval union"
                        } else {
                            null
                        },
                        "session_assignment" to "end_local_date",
                    ).withoutNullValues()
                } else {
                    null
                },
                "exercise_sessions" to exercises.takeIf { it.isNotEmpty() },
                "sources" to daily.sources,
            ).withoutNullValues()
        }
    }

    private fun normalizedSleepSessions(
        snapshot: HealthSnapshot,
        date: LocalDate,
        zone: ZoneId
    ): NormalizedSleepSessions {
        val candidates = snapshot.rawRecords["SleepSessionRecord"].orEmpty()
            .filter { record ->
                val end = parseInstant(record["endTime"] as? String) ?: return@filter false
                end.atZone(zone).toLocalDate() == date
            }
        val records = preferredRecords(
            candidates,
            listOf(OHEALTH, OHEALTH_ALT, GOOGLE_FIT)
        )

        val windows = records.mapNotNull { record ->
            val start = parseInstant(record["startTime"] as? String) ?: return@mapNotNull null
            val end = parseInstant(record["endTime"] as? String) ?: return@mapNotNull null
            if (!end.isAfter(start)) return@mapNotNull null
            SleepWindow(
                start = start,
                end = end,
                source = source(record),
                stages = record["stages"],
            )
        }.sortedWith(compareBy<SleepWindow> { it.start }.thenByDescending { it.end })

        if (windows.isEmpty()) {
            return NormalizedSleepSessions(emptyList(), 0, 0)
        }

        val merged = mutableListOf<SleepWindow>()
        windows.forEach { window ->
            val previous = merged.lastOrNull()
            if (previous == null || window.start.isAfter(previous.end)) {
                merged += window
            } else {
                merged[merged.lastIndex] = previous.copy(
                    end = maxOf(previous.end, window.end),
                    source = previous.source.takeIf { it == window.source },
                    stages = null,
                    mergedRawSessionCount =
                        previous.mergedRawSessionCount + window.mergedRawSessionCount,
                )
            }
        }

        val sessions = merged.map { window ->
            linkedMapOf<String, Any?>(
                "start_local" to window.start.atZone(zone).toString(),
                "end_local" to window.end.atZone(zone).toString(),
                "start_utc" to window.start.toString(),
                "end_utc" to window.end.toString(),
                "session_window_minutes" to Duration.between(window.start, window.end).toMinutes(),
                "source" to window.source,
                "merged_raw_session_count" to window.mergedRawSessionCount.takeIf { it > 1 },
                "stages" to window.stages,
            ).withoutNullValues()
        }
        val unionMinutes = sessions.sumOf {
            (it["session_window_minutes"] as? Number)?.toLong() ?: 0L
        }

        return NormalizedSleepSessions(
            sessions = sessions,
            rawSessionCount = windows.size,
            unionMinutes = unionMinutes,
        )
    }

    private fun sleepSummary(
        snapshot: HealthSnapshot,
        today: java.time.LocalDate,
        zone: ZoneId
    ): Map<String, Any?>? {
        val sleep = normalizedSleepSessions(snapshot, today, zone)
        val aggregateMinutes = snapshot.sleepDurationMinutes
        if (sleep.sessions.isEmpty() && aggregateMinutes == null) return null

        return linkedMapOf(
            "health_connect_aggregate_minutes" to aggregateMinutes,
            "session_window_union_minutes" to sleep.unionMinutes.takeIf {
                sleep.sessions.isNotEmpty()
            },
            "aggregate_minus_session_union_minutes" to if (
                aggregateMinutes != null && sleep.sessions.isNotEmpty()
            ) {
                aggregateMinutes - sleep.unionMinutes
            } else {
                null
            },
            "session_count" to sleep.sessions.size,
            "raw_session_count_before_dedup" to sleep.rawSessionCount,
            "sessions" to sleep.sessions,
            "first_start_local" to sleep.sessions.firstOrNull()?.get("start_local"),
            "last_end_local" to sleep.sessions.lastOrNull()?.get("end_local"),
            "aggregate_source" to snapshot.summarySources["sleep_duration_minutes"],
            "session_boundaries_source" to if (sleep.sessions.isNotEmpty()) {
                "raw SleepSessionRecord, normalized by interval union"
            } else {
                null
            },
            "session_assignment" to "end_local_date",
        ).withoutNullValues()
    }

    private fun exerciseSummary(
        aggregateMinutes: Long?,
        aggregateSource: String?,
        sessions: List<Map<String, Any?>>
    ): Map<String, Any?>? {
        if (aggregateMinutes == null && sessions.isEmpty()) return null

        val unionMinutes = intervalUnionMinutes(sessions)
        return linkedMapOf(
            "health_connect_aggregate_minutes" to aggregateMinutes,
            "session_window_union_minutes" to unionMinutes.takeIf { sessions.isNotEmpty() },
            "aggregate_minus_session_union_minutes" to if (
                aggregateMinutes != null && sessions.isNotEmpty()
            ) {
                aggregateMinutes - unionMinutes
            } else {
                null
            },
            "session_count" to sessions.size,
            "sessions" to sessions.takeIf { it.isNotEmpty() },
            "aggregate_source" to aggregateSource,
            "session_boundaries_source" to if (sessions.isNotEmpty()) {
                "raw ExerciseSessionRecord"
            } else {
                null
            },
        ).withoutNullValues()
    }

    private fun intervalUnionMinutes(sessions: List<Map<String, Any?>>): Long {
        val intervals = sessions.mapNotNull { session ->
            val start = parseInstant(session["start_utc"] as? String) ?: return@mapNotNull null
            val end = parseInstant(session["end_utc"] as? String) ?: return@mapNotNull null
            if (!end.isAfter(start)) return@mapNotNull null
            start to end
        }.sortedBy { it.first }
        if (intervals.isEmpty()) return 0L

        var union = 0L
        var currentStart = intervals.first().first
        var currentEnd = intervals.first().second
        intervals.drop(1).forEach { (start, end) ->
            if (start.isAfter(currentEnd)) {
                union += Duration.between(currentStart, currentEnd).toMinutes()
                currentStart = start
                currentEnd = end
            } else if (end.isAfter(currentEnd)) {
                currentEnd = end
            }
        }
        union += Duration.between(currentStart, currentEnd).toMinutes()
        return union
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
                "session_window_minutes" to Duration.between(start, end).toMinutes(),
                "source" to source(record),
            ).withoutNullValues()
        }.sortedBy { it["start_local"]?.toString() }
    }

    private fun dataQuality(
        snapshot: HealthSnapshot,
        today: LocalDate,
        zone: ZoneId,
        heartRate: Map<String, Any?>?,
        sleep: Map<String, Any?>?,
        exercise: Map<String, Any?>?
    ): Map<String, Any?> {
        val warnings = mutableListOf<Map<String, Any?>>()
        val wearableSources = setOf(OHEALTH, OHEALTH_ALT)

        val stepsSource = snapshot.summarySources["steps"]
        if (stepsSource != null && stepsSource !in wearableSources) {
            warnings += mapOf(
                "code" to "wearable_activity_fallback_today",
                "message" to "Today's steps are using a fallback source because OHealth has no selected aggregate yet.",
                "source" to stepsSource,
            )
        }

        val latestHeart = heartRate?.get("latest") as? Map<*, *>
        val latestHeartUtc = parseInstant(latestHeart?.get("at_utc") as? String)
        if (latestHeartUtc != null && latestHeartUtc.atZone(zone).toLocalDate() != today) {
            warnings += mapOf(
                "code" to "latest_heart_rate_is_from_previous_local_day",
                "message" to "Latest exported heart-rate sample is not from the current local day.",
                "latest_at_local" to latestHeart?.get("at_local"),
                "source" to latestHeart?.get("source"),
            )
        }

        fun mismatchWarning(
            code: String,
            label: String,
            summary: Map<String, Any?>?
        ) {
            val aggregate = number(summary?.get("health_connect_aggregate_minutes")) ?: return
            val union = number(summary?.get("session_window_union_minutes")) ?: return
            val difference = aggregate - union
            if (kotlin.math.abs(difference) >= 5.0) {
                warnings += mapOf(
                    "code" to code,
                    "message" to "$label Health Connect aggregate differs from the deduplicated session-window union.",
                    "aggregate_minutes" to aggregate.toLong(),
                    "session_window_union_minutes" to union.toLong(),
                    "difference_minutes" to difference.toLong(),
                )
            }
        }

        mismatchWarning("sleep_aggregate_session_mismatch", "Sleep", sleep)
        mismatchWarning("exercise_aggregate_session_mismatch", "Exercise", exercise)

        if (snapshot.extractionErrors.isNotEmpty()) {
            warnings += mapOf(
                "code" to "raw_extraction_errors",
                "message" to "One or more raw Health Connect record types failed to export.",
                "record_types" to snapshot.extractionErrors.keys.sorted(),
            )
        }

        return linkedMapOf(
            "warning_count" to warnings.size,
            "warnings" to warnings,
            "extraction_error_count" to snapshot.extractionErrors.size,
        )
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

    private fun preferredSource(
        sources: Collection<String?>,
        priority: List<String>
    ): String? {
        val available = sources.filterNotNull().toSet()
        priority.firstOrNull { it in available }?.let { return it }
        return sources.firstOrNull { it != null }
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
