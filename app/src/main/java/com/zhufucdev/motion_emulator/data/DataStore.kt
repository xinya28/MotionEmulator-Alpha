package com.zhufucdev.motion_emulator.data

import android.content.Context
import com.zhufucdev.me.stub.Data
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File

/**
 * Abstraction of method set to store and read
 * simulation data
 */
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalSerializationApi::class)
abstract class DataStore<T : Data> {
    private val data = sortedMapOf<String, T>()

    /** Last-modified stamp of the file each cached record was read from. */
    private val stamps = mutableMapOf<String, Long>()
    private lateinit var rootDir: File

    abstract val typeName: String
    protected abstract val dataSerializer: KSerializer<T>

    private val Data.storeName: String get() = "${typeName}_${id}.json"

    /**
     * Make sure it works
     *
     * Should be called before any I/O operation. Records whose file changed on disk (edits,
     * imports, restores done while this process was alive) are re-read instead of being served
     * from the in-memory cache, so an emulation never replays stale path data.
     */
    fun require(context: Context) {
        rootDir = context.filesDir

        val files = rootDir.list()
        if (files == null) {
            data.clear()
            stamps.clear()
        } else {
            val existingIds = mutableListOf<String>()
            files.forEach {
                val file = File(rootDir, it)
                if (!it.endsWith("json") || !it.startsWith(typeName))
                    return@forEach
                val id = file.nameWithoutExtension.removePrefix("${typeName}_")
                existingIds.add(id)
                val stamp = file.lastModified()
                if (data.containsKey(id) && stamps[id] == stamp) {
                    return@forEach
                }

                file.inputStream().use { s ->
                    val record = Json.decodeFromStream(dataSerializer, s)
                    data[record.id] = record
                    stamps[record.id] = stamp
                }
            }
            val removed = data.keys.filter { it !in existingIds }
            removed.forEach {
                data.remove(it)
                stamps.remove(it)
            }
        }
    }

    fun store(record: T, overwrite: Boolean = false) {
        if (data.containsKey(record.id) && !overwrite) return

        val file = File(rootDir, record.storeName)
        file.outputStream().use {
            record.writeTo(it)
        }
        data[record.id] = record
        stamps[record.id] = file.lastModified()
    }

    fun parseAndStore(string: String, overwrite: Boolean = false): T {
        val record = Json.decodeFromString(dataSerializer, string)
        if (data.containsKey(record.id) && !overwrite) return record

        val file = File(rootDir, record.storeName)
        file.writeText(string)
        data[record.id] = record
        stamps[record.id] = file.lastModified()
        return record
    }

    fun delete(record: T, context: Context) {
        context.deleteFile(record.storeName)
        data.remove(record.id)
        stamps.remove(record.id)
    }

    fun list() = data.values.toList()

    operator fun get(id: String) = data[id]

    override fun equals(other: Any?): Boolean =
        other is DataStore<*> && other::class == this::class && other.typeName == this.typeName

    override fun hashCode(): Int {
        var result = data.hashCode()
        result = 31 * result + rootDir.hashCode()
        result = 31 * result + typeName.hashCode()
        result = 31 * result + dataSerializer.hashCode()
        return result
    }
}