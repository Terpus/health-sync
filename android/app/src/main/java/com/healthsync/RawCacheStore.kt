package com.healthsync

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object RawCacheStore {
    private const val FILE_NAME = "health_raw_cache_v1.json"

    fun exists(context: Context): Boolean = cacheFile(context).isFile

    fun clear(context: Context) {
        cacheFile(context).delete()
    }

    fun load(context: Context): MutableMap<String, MutableList<Map<String, Any?>>> {
        val file = cacheFile(context)
        if (!file.isFile || file.length() == 0L) return linkedMapOf()

        FileInputStream(file).bufferedReader().use { buffered ->
            JsonReader(buffered).use { reader ->
                @Suppress("UNCHECKED_CAST")
                val root = readValue(reader) as? Map<String, Any?> ?: return linkedMapOf()
                @Suppress("UNCHECKED_CAST")
                val raw = root["raw_records"] as? Map<String, Any?> ?: return linkedMapOf()
                val output = linkedMapOf<String, MutableList<Map<String, Any?>>>()
                raw.forEach { (type, value) ->
                    val list = value as? List<*> ?: emptyList<Any?>()
                    @Suppress("UNCHECKED_CAST")
                    output[type] = list.mapNotNull {
                        it as? Map<String, Any?>
                    }.toMutableList()
                }
                return output
            }
        }
    }

    fun save(
        context: Context,
        records: Map<String, List<Map<String, Any?>>>
    ) {
        val target = cacheFile(context)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(temp, false).bufferedWriter().use { buffered ->
            JsonWriter(buffered).use { writer ->
                writer.beginObject()
                writer.name("version").value(1L)
                writer.name("raw_records")
                writeValue(writer, records)
                writer.endObject()
            }
        }
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IllegalStateException("Could not replace raw cache.")
        }
        if (!temp.renameTo(target)) {
            FileInputStream(temp).use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            temp.delete()
        }
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readValue(reader: JsonReader): Any? {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                reader.beginObject()
                while (reader.hasNext()) put(reader.nextName(), readValue(reader))
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> mutableListOf<Any?>().apply {
                reader.beginArray()
                while (reader.hasNext()) add(readValue(reader))
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
                null
            }
            else -> error("Unexpected cache JSON token: ${reader.peek()}")
        }
    }

    private fun writeValue(writer: JsonWriter, value: Any?) {
        when (value) {
            null -> writer.nullValue()
            is Map<*, *> -> {
                writer.beginObject()
                value.forEach { (key, item) ->
                    writer.name(key.toString())
                    writeValue(writer, item)
                }
                writer.endObject()
            }
            is Iterable<*> -> {
                writer.beginArray()
                value.forEach { writeValue(writer, it) }
                writer.endArray()
            }
            is Boolean -> writer.value(value)
            is Number -> writer.value(value)
            else -> writer.value(value.toString())
        }
    }
}
