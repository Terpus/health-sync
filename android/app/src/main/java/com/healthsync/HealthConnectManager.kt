package com.healthsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.lang.reflect.Method
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KClass

data class HealthSnapshot(
    val deviceId: String,
    val recordedAt: String,
    val exportStart: String,
    val exportEnd: String,
    val grantedPermissions: List<String>,
    val requestedRecordTypes: List<String>,
    val steps: Long?,
    val caloriesActive: Long?,
    val caloriesTotal: Long?,
    val heartRateAvg: Int?,
    val heartRateResting: Int?,
    val distanceMeters: Long?,
    val activeMinutes: Long?,
    val sleepDurationMinutes: Long?,
    val sleepScore: Int?,
    val sleepStart: String?,
    val sleepEnd: String?,
    val sleepStartUtc: String?,
    val sleepEndUtc: String?,
    val sleepDate: String?,
    val sleepStages: Map<String, Long>?,
    val hrvRmssdAvgMs: Double?,
    val hrvRmssdMedianMs: Double?,
    val hrvRmssdMinMs: Double?,
    val hrvRmssdMaxMs: Double?,
    val hrvRmssdSampleCount: Int,
    val selectedSummaryOrigin: String?,
    val summarySources: Map<String, String>,
    val summaryDataOrigins: List<String>,
    val allSourcesSteps: Long?,
    val allSourcesDistanceMeters: Long?,
    val allSourcesCaloriesTotal: Long?,
    val rawRecords: Map<String, List<Map<String, Any?>>>,
    val extractionErrors: Map<String, String>
)

class HealthConnectManager(private val context: Context) {

    enum class Availability {
        AVAILABLE,
        INSTALL_OR_UPDATE_REQUIRED,
        UNAVAILABLE
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    private val supportedRecordTypes: List<KClass<out Record>> = listOf(
        ActiveCaloriesBurnedRecord::class,
        BasalBodyTemperatureRecord::class,
        BasalMetabolicRateRecord::class,
        BloodGlucoseRecord::class,
        BloodPressureRecord::class,
        BodyFatRecord::class,
        BodyTemperatureRecord::class,
        BodyWaterMassRecord::class,
        BoneMassRecord::class,
        CervicalMucusRecord::class,
        CyclingPedalingCadenceRecord::class,
        DistanceRecord::class,
        ElevationGainedRecord::class,
        ExerciseSessionRecord::class,
        FloorsClimbedRecord::class,
        HeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        HeightRecord::class,
        HydrationRecord::class,
        IntermenstrualBleedingRecord::class,
        LeanBodyMassRecord::class,
        MenstruationFlowRecord::class,
        MenstruationPeriodRecord::class,
        NutritionRecord::class,
        OvulationTestRecord::class,
        OxygenSaturationRecord::class,
        PlannedExerciseSessionRecord::class,
        PowerRecord::class,
        RespiratoryRateRecord::class,
        RestingHeartRateRecord::class,
        SexualActivityRecord::class,
        SkinTemperatureRecord::class,
        SleepSessionRecord::class,
        SpeedRecord::class,
        StepsCadenceRecord::class,
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
        Vo2MaxRecord::class,
        WeightRecord::class,
        WheelchairPushesRecord::class,
    )

    val permissions = supportedRecordTypes
        .map { HealthPermission.getReadPermission(it) }
        .toSet() + setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )

    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun availability(): Availability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Availability.INSTALL_OR_UPDATE_REQUIRED
            }
            else -> Availability.UNAVAILABLE
        }
    }

    fun installOrUpdateIntent(): Intent {
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=com.google.android.apps.healthdata")
        ).apply {
            setPackage("com.android.vending")
        }
    }

    fun managePermissionsIntent(): Intent {
        return Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    suspend fun grantedPermissions(): Set<String> {
        if (availability() != Availability.AVAILABLE) return emptySet()
        return client.permissionController.getGrantedPermissions()
    }

    suspend fun hasPermissions(): Boolean {
        if (availability() != Availability.AVAILABLE) return false
        return grantedPermissions().containsAll(requiredPermissions)
    }

    suspend fun readTodaySnapshot(
        onProgress: (String) -> Unit = {}
    ): HealthSnapshot {
        check(availability() == Availability.AVAILABLE) {
            "Health Connect is not available. Install or update Health Connect first."
        }

        val granted = grantedPermissions()
        onProgress("Health Connect permissions: ${granted.size}/${permissions.size} granted")

        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val todayRange = TimeRangeFilter.between(startOfDay, now)
        val exportStart = now.minusSeconds(EXPORT_HISTORY_DAYS * 24 * 60 * 60)
        val exportRange = TimeRangeFilter.between(exportStart, now)

        // Sleep: look back 24h to catch last night
        val sleepRange = TimeRangeFilter.between(
            now.minusSeconds(86400),
            now
        )

        onProgress("Reading sleep/HRV/daily aggregates")
        val sleep = readOptional { readSleep(sleepRange) }
        val hrv = readOptional { readHrvStats(sleepRange) }
        val summary = readOptional { readDailySummary(todayRange) }
        onProgress("Reading raw records for $EXPORT_HISTORY_DAYS days")
        val export = readRawRecords(exportRange, granted, onProgress)
        onProgress("Health Connect extraction complete")

        return HealthSnapshot(
            deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ),
            recordedAt = ZonedDateTime.now().toString(),
            exportStart = exportStart.toString(),
            exportEnd = now.toString(),
            grantedPermissions = granted.sorted(),
            requestedRecordTypes = supportedRecordTypes.map { it.java.simpleName }.sorted(),
            steps = summary?.steps,
            caloriesActive = summary?.caloriesActive,
            caloriesTotal = summary?.caloriesTotal,
            heartRateAvg = summary?.heartRateAvg,
            heartRateResting = summary?.heartRateResting,
            distanceMeters = summary?.distanceMeters,
            activeMinutes = summary?.exerciseMinutes,
            sleepDurationMinutes = summary?.sleepDurationMinutes ?: sleep?.durationMinutes,
            sleepScore = sleep?.score,
            sleepStart = sleep?.start,
            sleepEnd = sleep?.end,
            sleepStartUtc = sleep?.startUtc,
            sleepEndUtc = sleep?.endUtc,
            sleepDate = sleep?.sleepDate,
            sleepStages = sleep?.stages,
            hrvRmssdAvgMs = hrv?.averageMs,
            hrvRmssdMedianMs = hrv?.medianMs,
            hrvRmssdMinMs = hrv?.minMs,
            hrvRmssdMaxMs = hrv?.maxMs,
            hrvRmssdSampleCount = hrv?.sampleCount ?: 0,
            selectedSummaryOrigin = summary?.selectedOrigin,
            summarySources = summary?.sources.orEmpty(),
            summaryDataOrigins = summary?.dataOrigins.orEmpty(),
            allSourcesSteps = summary?.allSourcesSteps,
            allSourcesDistanceMeters = summary?.allSourcesDistanceMeters,
            allSourcesCaloriesTotal = summary?.allSourcesCaloriesTotal,
            rawRecords = export.records,
            extractionErrors = export.errors
        )
    }

    private suspend fun <T> readOptional(block: suspend () -> T?): T? {
        return try {
            block()
        } catch (e: SecurityException) {
            null
        }
    }

    data class DailySummary(
        val steps: Long?,
        val caloriesActive: Long?,
        val caloriesTotal: Long?,
        val heartRateAvg: Int?,
        val heartRateResting: Int?,
        val distanceMeters: Long?,
        val exerciseMinutes: Long?,
        val sleepDurationMinutes: Long?,
        val selectedOrigin: String?,
        val sources: Map<String, String>,
        val dataOrigins: List<String>,
        val allSourcesSteps: Long?,
        val allSourcesDistanceMeters: Long?,
        val allSourcesCaloriesTotal: Long?
    )

    private suspend fun readDailySummary(range: TimeRangeFilter): DailySummary {
        val allSourcesResult = aggregateDailyMetrics(range, emptySet())
        val availablePackages = allSourcesResult.dataOrigins.map { it.packageName }.toSet()
        val sourceResults = availablePackages.associateWith { packageName ->
            aggregateDailyMetrics(range, setOf(DataOrigin(packageName)))
        }

        fun sourceFor(
            recordType: KClass<out Record>,
            hasValue: (String) -> Boolean
        ): String? = prioritizedOrigins(recordType, availablePackages).firstOrNull(hasValue)

        val stepsSource = sourceFor(StepsRecord::class) {
            sourceResults[it]?.get(StepsRecord.COUNT_TOTAL) != null
        }
        val activeCaloriesSource = sourceFor(ActiveCaloriesBurnedRecord::class) {
            sourceResults[it]?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) != null
        }
        val totalCaloriesSource = sourceFor(TotalCaloriesBurnedRecord::class) {
            sourceResults[it]?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL) != null
        }
        val heartRateSource = sourceFor(HeartRateRecord::class) {
            sourceResults[it]?.get(HeartRateRecord.BPM_AVG) != null
        }
        val restingHeartRateSource = sourceFor(RestingHeartRateRecord::class) {
            sourceResults[it]?.get(RestingHeartRateRecord.BPM_AVG) != null
        }
        val distanceSource = sourceFor(DistanceRecord::class) {
            sourceResults[it]?.get(DistanceRecord.DISTANCE_TOTAL) != null
        }
        val exerciseSource = sourceFor(ExerciseSessionRecord::class) {
            sourceResults[it]?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL) != null
        }
        val sleepSource = sourceFor(SleepSessionRecord::class) {
            sourceResults[it]?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL) != null
        }

        val summarySources = linkedMapOf<String, String>().apply {
            stepsSource?.let { put("steps", it) }
            activeCaloriesSource?.let { put("calories_active_kcal", it) }
            totalCaloriesSource?.let { put("calories_total_kcal", it) }
            heartRateSource?.let { put("heart_rate_sample_avg_bpm", it) }
            restingHeartRateSource?.let { put("heart_rate_resting_bpm", it) }
            distanceSource?.let { put("distance_health_connect_km", it) }
            exerciseSource?.let { put("exercise_session_minutes", it) }
            sleepSource?.let { put("sleep_duration_minutes", it) }
        }

        return DailySummary(
            steps = stepsSource?.let { sourceResults[it]?.get(StepsRecord.COUNT_TOTAL) }
                ?: allSourcesResult[StepsRecord.COUNT_TOTAL],
            caloriesActive = activeCaloriesSource
                ?.let { sourceResults[it]?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) }
                ?.inKilocalories
                ?.toLong(),
            caloriesTotal = totalCaloriesSource
                ?.let { sourceResults[it]?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL) }
                ?.inKilocalories
                ?.toLong()
                ?: allSourcesResult[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong(),
            heartRateAvg = heartRateSource
                ?.let { sourceResults[it]?.get(HeartRateRecord.BPM_AVG) }
                ?.toInt(),
            heartRateResting = restingHeartRateSource
                ?.let { sourceResults[it]?.get(RestingHeartRateRecord.BPM_AVG) }
                ?.toInt(),
            distanceMeters = distanceSource
                ?.let { sourceResults[it]?.get(DistanceRecord.DISTANCE_TOTAL) }
                ?.inMeters
                ?.toLong()
                ?: allSourcesResult[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toLong(),
            exerciseMinutes = exerciseSource
                ?.let { sourceResults[it]?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL) }
                ?.toMinutes(),
            sleepDurationMinutes = sleepSource
                ?.let { sourceResults[it]?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL) }
                ?.toMinutes(),
            selectedOrigin = summarySources.values.distinct().singleOrNull(),
            sources = summarySources,
            dataOrigins = availablePackages.sorted(),
            allSourcesSteps = allSourcesResult[StepsRecord.COUNT_TOTAL],
            allSourcesDistanceMeters = allSourcesResult[DistanceRecord.DISTANCE_TOTAL]
                ?.inMeters
                ?.toLong(),
            allSourcesCaloriesTotal = allSourcesResult[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                ?.inKilocalories
                ?.toLong()
        )
    }

    private suspend fun aggregateDailyMetrics(
        range: TimeRangeFilter,
        dataOrigins: Set<DataOrigin>
    ) = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    HeartRateRecord.BPM_AVG,
                    RestingHeartRateRecord.BPM_AVG,
                    DistanceRecord.DISTANCE_TOTAL,
                    ExerciseSessionRecord.EXERCISE_DURATION_TOTAL,
                    SleepSessionRecord.SLEEP_DURATION_TOTAL,
                ),
                timeRangeFilter = range,
                dataOriginFilter = dataOrigins,
            )
        )

    private fun prioritizedOrigins(
        recordType: KClass<out Record>,
        availablePackages: Set<String>
    ): List<String> {
        val configured = SOURCE_PRIORITY_BY_RECORD_TYPE[recordType] ?: DEFAULT_SOURCE_PRIORITY
        return (
            configured.filter { it in availablePackages } +
                availablePackages.filter { it !in configured }.sorted()
            ).distinct()
    }

    data class RawExport(
        val records: Map<String, List<Map<String, Any?>>>,
        val errors: Map<String, String>
    )

    data class HrvStats(
        val averageMs: Double,
        val medianMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val sampleCount: Int
    )

    private suspend fun readHrvStats(range: TimeRangeFilter): HrvStats? {
        val values = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateVariabilityRmssdRecord::class,
                timeRangeFilter = range,
                ascendingOrder = true,
            )
        ).records.map { it.heartRateVariabilityMillis }.sorted()

        if (values.isEmpty()) return null

        val middle = values.size / 2
        val median = if (values.size % 2 == 0) {
            (values[middle - 1] + values[middle]) / 2.0
        } else {
            values[middle]
        }

        return HrvStats(
            averageMs = roundOneDecimal(values.average()),
            medianMs = roundOneDecimal(median),
            minMs = roundOneDecimal(values.first()),
            maxMs = roundOneDecimal(values.last()),
            sampleCount = values.size
        )
    }

    private fun roundOneDecimal(value: Double): Double {
        return kotlin.math.round(value * 10.0) / 10.0
    }

    private suspend fun readRawRecords(
        range: TimeRangeFilter,
        grantedPermissions: Set<String>,
        onProgress: (String) -> Unit,
    ): RawExport {
        val recordsByType = linkedMapOf<String, List<Map<String, Any?>>>()
        val errorsByType = linkedMapOf<String, String>()

        for (recordType in supportedRecordTypes) {
            val name = recordType.java.simpleName
            val permission = HealthPermission.getReadPermission(recordType)
            if (permission !in grantedPermissions) {
                recordsByType[name] = emptyList()
                errorsByType[name] = "Permission not granted"
                onProgress("raw[$name] skipped: permission not granted")
                continue
            }

            try {
                onProgress("raw[$name] reading")
                val records = readRecordsUntyped(recordType, range)
                recordsByType[name] = records.map { recordToMap(it) }
                onProgress("raw[$name] count=${records.size}")
            } catch (e: SecurityException) {
                recordsByType[name] = emptyList()
                errorsByType[name] = "Permission not granted"
                onProgress("raw[$name] security error")
            } catch (e: Exception) {
                recordsByType[name] = emptyList()
                errorsByType[name] = e.message ?: e.javaClass.simpleName
                onProgress("raw[$name] error=${e.javaClass.simpleName}: ${e.message}")
            }
        }

        return RawExport(recordsByType, errorsByType)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun readRecordsUntyped(
        recordType: KClass<out Record>,
        range: TimeRangeFilter
    ): List<Record> {
        val allRecords = mutableListOf<Record>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType as KClass<Record>,
                    timeRangeFilter = range,
                    ascendingOrder = true,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                )
            )
            allRecords += response.records
            pageToken = response.pageToken
        } while (pageToken != null && allRecords.size < MAX_RECORDS_PER_TYPE)

        return allRecords.take(MAX_RECORDS_PER_TYPE)
    }

    private fun recordToMap(record: Record): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "record_type" to record.javaClass.simpleName,
        ) + objectToMap(record, 0)
    }

    private fun objectToMap(value: Any, depth: Int): Map<String, Any?> {
        val output = linkedMapOf<String, Any?>()
        value.javaClass.methods
            .asSequence()
            .filter { it.isPublicGetter() }
            .sortedBy { it.name }
            .forEach { method ->
                val name = method.propertyName()
                val propertyValue = try {
                    method.invoke(value)
                } catch (e: Exception) {
                    null
                }
                output[name] = toJsonSafeValue(propertyValue, depth + 1)
            }
        return output
    }

    private fun Method.isPublicGetter(): Boolean {
        if (parameterTypes.isNotEmpty()) return false
        if (name == "getClass") return false
        if (returnType == Void.TYPE) return false
        return name.startsWith("get") || name.startsWith("is")
    }

    private fun Method.propertyName(): String {
        val rawName = if (name.startsWith("get")) name.removePrefix("get") else name.removePrefix("is")
        return rawName.replaceFirstChar { it.lowercase() }
    }

    private fun toJsonSafeValue(value: Any?, depth: Int): Any? {
        if (value == null) return null
        if (depth > MAX_SERIALIZATION_DEPTH) return value.toString()

        return when (value) {
            is String, is Number, is Boolean -> value
            is Instant -> value.toString()
            is java.time.LocalDate -> value.toString()
            is java.time.LocalDateTime -> value.toString()
            is java.time.ZonedDateTime -> value.toString()
            is java.time.OffsetDateTime -> value.toString()
            is java.time.ZoneOffset -> value.toString()
            is Enum<*> -> value.name
            is Map<*, *> -> value.entries.associate { (key, entryValue) ->
                key.toString() to toJsonSafeValue(entryValue, depth + 1)
            }
            is Iterable<*> -> value.map { toJsonSafeValue(it, depth + 1) }
            else -> {
                val className = value.javaClass.name
                if (
                    className.startsWith("androidx.health.connect") ||
                    className.startsWith("androidx.health.platform")
                ) {
                    objectToMap(value, depth)
                } else {
                    value.toString()
                }
            }
        }
    }

    data class SleepData(
        val durationMinutes: Long,
        val score: Int?,
        val start: String,
        val end: String,
        val startUtc: String,
        val endUtc: String,
        val sleepDate: String,
        val stages: Map<String, Long>?
    )

    private suspend fun readSleep(range: TimeRangeFilter): SleepData? {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = range,
                ascendingOrder = true,
            )
        ).records
        val session = records.maxByOrNull { it.endTime } ?: return null

        val durationMinutes = (session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60000
        val zone = ZoneId.systemDefault()
        val localStart = ZonedDateTime.ofInstant(session.startTime, zone)
        val localEnd = ZonedDateTime.ofInstant(session.endTime, zone)

        val stages = session.stages
            .groupBy { it.stage }
            .mapValues { (_, list) ->
                list.sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() } / 60000
            }
            .mapKeys { (stage, _) ->
                when (stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                    SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
                    else -> "unknown"
                }
            }

        return SleepData(
            durationMinutes = durationMinutes,
            score = null,
            start = localStart.toString(),
            end = localEnd.toString(),
            startUtc = session.startTime.toString(),
            endUtc = session.endTime.toString(),
            sleepDate = localEnd.toLocalDate().toString(),
            stages = stages.takeIf { it.isNotEmpty() }
        )
    }

    companion object {
        private const val EXPORT_HISTORY_DAYS = 30L
        private const val PAGE_SIZE = 500
        private const val MAX_RECORDS_PER_TYPE = 2_000
        private const val MAX_SERIALIZATION_DEPTH = 5
        private const val OHEALTH_INTERNATIONAL_PACKAGE = "com.heytap.health.international"
        private const val OHEALTH_PACKAGE = "com.heytap.health"
        private const val GOOGLE_FIT_PACKAGE = "com.google.android.apps.fitness"
        private const val BODY_DIARY_PACKAGE = "com.selantoapps.bodydiary"

        private val OHEALTH_PACKAGES = listOf(
            OHEALTH_INTERNATIONAL_PACKAGE,
            OHEALTH_PACKAGE,
        )

        private val DEFAULT_SOURCE_PRIORITY = OHEALTH_PACKAGES + GOOGLE_FIT_PACKAGE

        // Per-record overrides make source selection configurable without changing aggregation logic.
        // If a future smart scale writes directly to Health Connect, add its package here.
        private val SOURCE_PRIORITY_BY_RECORD_TYPE: Map<KClass<out Record>, List<String>> = mapOf(
            WeightRecord::class to (OHEALTH_PACKAGES + BODY_DIARY_PACKAGE + GOOGLE_FIT_PACKAGE),
            BodyFatRecord::class to (OHEALTH_PACKAGES + BODY_DIARY_PACKAGE + GOOGLE_FIT_PACKAGE),
            BodyWaterMassRecord::class to (OHEALTH_PACKAGES + BODY_DIARY_PACKAGE + GOOGLE_FIT_PACKAGE),
            BoneMassRecord::class to (OHEALTH_PACKAGES + BODY_DIARY_PACKAGE + GOOGLE_FIT_PACKAGE),
            LeanBodyMassRecord::class to (OHEALTH_PACKAGES + BODY_DIARY_PACKAGE + GOOGLE_FIT_PACKAGE),
        )
    }
}
