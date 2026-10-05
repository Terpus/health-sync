package com.healthsync

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

object DriveClient {

    private const val FILE_NAME = "health_data.json"
    private const val PREFS = "health_sync"
    private const val KEY_FILE_URI = "drive_file_uri"

    data class SyncResult(
        val destination: String,
        val bytesWritten: Long
    )

    fun hasFile(context: Context): Boolean {
        val uri = fileUri(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }

    fun saveFileUri(context: Context, uri: Uri, flags: Int) {
        val persistFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        require(persistFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            "The selected document did not grant write access."
        }
        context.contentResolver.takePersistableUriPermission(uri, persistFlags)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FILE_URI, uri.toString())
            .apply()
    }

    fun describeFileAccess(context: Context): String {
        val uri = fileUri(context) ?: return "uri=<none>; persistedRead=false; persistedWrite=false"
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        var displayName: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return "uri=$uri; persistedRead=${permission?.isReadPermission == true}; " +
            "persistedWrite=${permission?.isWritePermission == true}; " +
            "name=${displayName ?: "<unknown>"}; size=${size ?: -1}"
    }

    fun syncSnapshot(context: Context, snapshot: HealthSnapshot): SyncResult {
        val uri = fileUri(context)
            ?: throw Exception("Google Drive file not connected. Tap 'Connect Google Drive' first.")

        SyncDiagnostics.log(context, "Drive access: ${describeFileAccess(context)}")
        val summaryEntry = snapshotToJson(snapshot, includeRawRecords = false)
        val existing = readSummaryFile(context, uri)
        val updated = if (existing != null) {
            mergeEntry(existing, summaryEntry, snapshot)
        } else {
            JSONObject().apply {
                put("profile", JSONObject().apply {
                    put("device_id", snapshot.deviceId)
                    put("last_updated", snapshot.recordedAt)
                })
                put("snapshots", JSONArray().put(summaryEntry))
            }
        }

        SyncDiagnostics.memory(context, "before serialization")
        val tempFile = serializeToTempFile(context, updated, snapshot)
        SyncDiagnostics.log(context, "serialization complete; bytes=${tempFile.length()}")
        SyncDiagnostics.memory(context, "after serialization")
        try {
            copyTempToUri(context, tempFile, uri)
            SyncDiagnostics.log(context, "document write complete")
            return SyncResult(uri.toString(), tempFile.length())
        } finally {
            tempFile.delete()
        }
    }

    fun exportLocalDownload(context: Context, snapshot: HealthSnapshot): SyncResult {
        val summaryEntry = snapshotToJson(snapshot, includeRawRecords = false)
        val root = JSONObject().apply {
            put("profile", JSONObject().apply {
                put("device_id", snapshot.deviceId)
                put("last_updated", snapshot.recordedAt)
            })
            put("snapshots", JSONArray().put(summaryEntry))
        }

        SyncDiagnostics.memory(context, "before local serialization")
        val tempFile = serializeToTempFile(context, root, snapshot)
        SyncDiagnostics.log(context, "local serialization complete; bytes=${tempFile.length()}")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "health_data_local_test.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/HealthSync"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IOException("Could not create local Downloads export.")

                copyTempToUri(context, tempFile, uri)
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                return SyncResult(
                    "Downloads/HealthSync/health_data_local_test.json",
                    tempFile.length()
                )
            }

            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            directory.mkdirs()
            val file = File(directory, "health_data_local_test.json")
            FileInputStream(tempFile).use { input ->
                FileOutputStream(file, false).use { output -> input.copyTo(output, 64 * 1024) }
            }
            return SyncResult(file.absolutePath, tempFile.length())
        } finally {
            tempFile.delete()
        }
    }

    private fun fileUri(context: Context): Uri? {
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FILE_URI, null)
        return uri?.let(Uri::parse)
    }

    private fun mergeEntry(existing: JSONObject, newEntry: JSONObject, snapshot: HealthSnapshot): JSONObject {
        val snapshots = existing.optJSONArray("snapshots") ?: org.json.JSONArray()
        val today = newEntry.getString("date")

        val kept = org.json.JSONArray()
        for (i in 0 until snapshots.length()) {
            val entry = snapshots.getJSONObject(i)
            val date = entry.optString("date")
            // Compact daily summaries are cheap: retain history and only replace today's entry.
            if (date != today) kept.put(entry)
        }
        kept.put(newEntry)

        existing.put("snapshots", kept)
        existing.optJSONObject("profile")?.put("last_updated", snapshot.recordedAt)
        return existing
    }

    private fun readSummaryFile(context: Context, uri: Uri): JSONObject? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return try {
            JsonReader(input.bufferedReader()).use { reader ->
                if (reader.peek() == JsonToken.END_DOCUMENT) return null
                val root = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "latest_full_export") {
                        reader.skipValue()
                    } else {
                        root.put(name, readJsonValue(reader))
                    }
                }
                reader.endObject()
                root
            }
        } catch (_: EOFException) {
            null
        }
    }

    private fun readJsonValue(reader: JsonReader): Any {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) put(reader.nextName(), readJsonValue(reader))
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) put(readJsonValue(reader))
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> {
                reader.nextNull()
                JSONObject.NULL
            }
            else -> throw IOException("Unexpected JSON token: ${reader.peek()}")
        }
    }

    private fun serializeToTempFile(
        context: Context,
        root: JSONObject,
        snapshot: HealthSnapshot
    ): File {
        val temp = File.createTempFile("health_sync_", ".json", context.cacheDir)
        try {
            FileOutputStream(temp).bufferedWriter().use { buffered ->
                JsonWriter(buffered).use { writer ->
                    writer.beginObject()
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        writer.name(key)
                        writeJsonValue(writer, root.get(key))
                    }
                    writer.name("latest_full_export")
                    writeFullSnapshot(writer, snapshot)
                    writer.endObject()
                }
            }
            return temp
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun writeFullSnapshot(writer: JsonWriter, snapshot: HealthSnapshot) {
        val summary = snapshotToJson(snapshot, includeRawRecords = false)
        writer.beginObject()
        val keys = summary.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            writer.name(key)
            writeJsonValue(writer, summary.get(key))
        }
        writer.name("raw_records")
        writeJsonValue(writer, snapshot.rawRecords)
        writer.name("raw_record_counts")
        writer.beginObject()
        snapshot.rawRecords.forEach { (type, records) ->
            writer.name(type).value(records.size.toLong())
        }
        writer.endObject()
        writer.name("extraction_errors")
        writeJsonValue(writer, snapshot.extractionErrors)
        writer.endObject()
    }

    private fun writeJsonValue(writer: JsonWriter, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> writer.nullValue()
            is JSONObject -> {
                writer.beginObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    writer.name(key)
                    writeJsonValue(writer, value.get(key))
                }
                writer.endObject()
            }
            is JSONArray -> {
                writer.beginArray()
                for (i in 0 until value.length()) writeJsonValue(writer, value.get(i))
                writer.endArray()
            }
            is Map<*, *> -> {
                writer.beginObject()
                value.forEach { (key, item) ->
                    writer.name(key.toString())
                    writeJsonValue(writer, item)
                }
                writer.endObject()
            }
            is Iterable<*> -> {
                writer.beginArray()
                value.forEach { writeJsonValue(writer, it) }
                writer.endArray()
            }
            is Array<*> -> {
                writer.beginArray()
                value.forEach { writeJsonValue(writer, it) }
                writer.endArray()
            }
            is Boolean -> writer.value(value)
            is Number -> writer.value(value)
            is String -> writer.value(value)
            else -> writer.value(value.toString())
        }
    }

    private fun copyTempToUri(context: Context, tempFile: File, uri: Uri) {
        FileInputStream(tempFile).use { input ->
            openTruncatingOutputStream(context, uri).use { output ->
                input.copyTo(output, 64 * 1024)
                output.flush()
            }
        }
    }

    private fun openTruncatingOutputStream(context: Context, uri: Uri): OutputStream {
        var lastError: Exception? = null
        for (mode in listOf("rwt", "wt")) {
            try {
                context.contentResolver.openOutputStream(uri, mode)?.let {
                    SyncDiagnostics.log(context, "opened destination with mode=$mode")
                    return it
                }
            } catch (e: Exception) {
                lastError = e
                SyncDiagnostics.log(
                    context,
                    "openOutputStream mode=$mode failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        throw IOException("Could not open $FILE_NAME for truncating write.", lastError)
    }

    private fun snapshotToJson(snapshot: HealthSnapshot, includeRawRecords: Boolean): JSONObject {
        val journalSummary = JournalHealthSummaryBuilder.build(snapshot)
        return JSONObject().apply {
            put("date", LocalDate.now().toString())
            put("recorded_at", snapshot.recordedAt)
            put("export_window", JSONObject().apply {
                put("start", snapshot.exportStart)
                put("end", snapshot.exportEnd)
            })
            put("granted_permissions", toJsonValue(snapshot.grantedPermissions))
            put("requested_record_types", toJsonValue(snapshot.requestedRecordTypes))
            snapshot.selectedSummaryOrigin?.let { put("selected_summary_origin", it) }
            put("summary_sources", toJsonValue(snapshot.summarySources))
            put("summary_data_origins", toJsonValue(snapshot.summaryDataOrigins))
            put("summary_method", "Health Connect aggregate API with per-metric source selection. OHealth is preferred when it has that metric, then configured fallbacks such as Google Fit; raw records remain typed by source record.")
            put("raw_sync", JSONObject().apply {
                put("mode", snapshot.rawSyncMode)
                put("rolling_window_days", 7)
                put("changes_applied", snapshot.rawChangesApplied)
                put("backfilled_types", snapshot.rawBackfilledTypes)
            })
            put("journal_summary", toJsonValue(journalSummary))
            put("analysis_guidance", JSONObject().apply {
                put("daily_totals_authoritative_source", "For today use journal_summary.activity_today/vitals/sleep. For any date in the rolling window use journal_summary.rolling_daily_summaries; those totals are queried directly from Health Connect per local day with per-metric source selection.")
                put("historical_session_details", "Sleep and exercise session boundaries inside rolling_daily_summaries come from source-prioritized raw records; aggregate daily totals remain authoritative when they differ from summed session windows.")
                put("raw_records_warning", "Do not sum raw_records to answer daily totals unless explicitly doing raw-record auditing; raw records can overlap within one origin and across origins, use UTC timestamps, and may not match app-local day cards.")
                put("timezone_rule", "For user-facing sleep and day-level answers, prefer local fields and local-day summaries over UTC timestamps ending in Z.")
                put("source_rule", "Source priority is selected independently per metric. OHealth is preferred when it has data for that metric; Google Fit and other origins are fallbacks.")
            })
            put("all_sources_audit", JSONObject().apply {
                snapshot.allSourcesSteps?.let { put("steps", it) }
                snapshot.allSourcesDistanceMeters?.let {
                    put("distance_health_connect_km", Math.round(it / 1000.0 * 100) / 100.0)
                }
                snapshot.allSourcesCaloriesTotal?.let { put("calories_total_kcal", it) }
                put("note", "Audit-only aggregate across all Health Connect origins. Do not use it in place of the per-metric selected summary values.")
            })
            snapshot.steps?.let { put("steps", it) }
            snapshot.caloriesActive?.let { put("calories_active_kcal", it) }
            snapshot.caloriesTotal?.let { put("calories_total_kcal", it) }
            snapshot.heartRateAvg?.let { put("heart_rate_sample_avg_bpm", it) }
            snapshot.heartRateResting?.let { put("heart_rate_resting_bpm", it) }
            snapshot.distanceMeters?.let {
                put("distance_health_connect_km", Math.round(it / 1000.0 * 100) / 100.0)
            }
            snapshot.activeMinutes?.let { put("exercise_session_minutes", it) }
            if (snapshot.sleepDurationMinutes != null) {
                val detailedSleep = journalSummary["sleep"] as? Map<*, *>
                put("sleep", JSONObject().apply {
                    put("duration_minutes", snapshot.sleepDurationMinutes)
                    put("duration_hours", Math.round(snapshot.sleepDurationMinutes / 60.0 * 100) / 100.0)
                    snapshot.sleepDate?.let { put("sleep_date_local", it) }
                    detailedSleep?.get("session_count")?.let { put("session_count", it) }
                    detailedSleep?.get("first_start_local")?.let { put("first_start_local", it) }
                    detailedSleep?.get("last_end_local")?.let { put("last_end_local", it) }
                    detailedSleep?.get("sessions")?.let { put("sessions", toJsonValue(it)) }
                    snapshot.sleepStages?.let { put("stages_minutes", JSONObject(it as Map<*, *>)) }
                })
            }
            if (snapshot.hrvRmssdSampleCount > 0) {
                put("hrv_rmssd", JSONObject().apply {
                    put("sample_count", snapshot.hrvRmssdSampleCount)
                    snapshot.hrvRmssdMedianMs?.let { put("median_ms", it) }
                    snapshot.hrvRmssdAvgMs?.let { put("average_ms", it) }
                    snapshot.hrvRmssdMinMs?.let { put("min_ms", it) }
                    snapshot.hrvRmssdMaxMs?.let { put("max_ms", it) }
                    put("display_guidance", "Use median_ms for Fitbit/Health-style HRV card comparison; average_ms is a separate arithmetic average.")
                })
            }
            if (includeRawRecords) {
                put("raw_records", toJsonValue(snapshot.rawRecords))
                put("raw_record_counts", JSONObject().apply {
                    snapshot.rawRecords.forEach { (type, records) ->
                        put(type, records.size)
                    }
                })
                put("extraction_errors", toJsonValue(snapshot.extractionErrors))
            }
        }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject -> value
            is org.json.JSONArray -> value
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, entryValue) ->
                    put(key.toString(), toJsonValue(entryValue))
                }
            }
            is Iterable<*> -> org.json.JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }
            is Array<*> -> org.json.JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }
            is Boolean, is Number, is String -> value
            else -> value.toString()
        }
    }
}
