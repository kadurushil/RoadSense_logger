package com.bajajauto.roadsense.canedge.ingestion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reconciliation state for a single RoadSense drive session's CANedge2 logs.
 */
enum class CanSessionSyncStatus {
    PENDING,
    WAITING_FINAL_CHUNK,
    PARTIAL,
    COMPLETE;

    companion object {
        fun fromStringOrDefault(raw: String?, fallback: CanSessionSyncStatus = PENDING): CanSessionSyncStatus {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback
        }
    }
}

/**
 * Persistent sync record for a single RoadSense drive session.
 */
data class CanSessionSyncRecord(
    val sessionId: String,
    val sessionDirName: String,
    val startTimeWallMs: Long,
    val stopTimeWallMs: Long? = null,
    val status: CanSessionSyncStatus = CanSessionSyncStatus.PENDING,
    val clockOffsetMs: Long? = null,
    val matchedRemotePaths: List<String> = emptyList(),
    val stagedLocalNames: List<String> = emptyList(),
    val lastAttemptMs: Long = 0L,
    val notes: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("sessionId", sessionId)
        obj.put("sessionDirName", sessionDirName)
        obj.put("startTimeWallMs", startTimeWallMs)
        if (stopTimeWallMs != null) obj.put("stopTimeWallMs", stopTimeWallMs)
        obj.put("status", status.name)
        if (clockOffsetMs != null) obj.put("clockOffsetMs", clockOffsetMs)
        obj.put("matchedRemotePaths", JSONArray(matchedRemotePaths))
        obj.put("stagedLocalNames", JSONArray(stagedLocalNames))
        obj.put("lastAttemptMs", lastAttemptMs)
        if (!notes.isNullOrBlank()) obj.put("notes", notes)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): CanSessionSyncRecord {
            val matchedArr = obj.optJSONArray("matchedRemotePaths")
            val matched = buildList {
                if (matchedArr != null) {
                    for (i in 0 until matchedArr.length()) {
                        val v = matchedArr.optString(i, "")
                        if (v.isNotBlank()) add(v)
                    }
                }
            }
            val stagedArr = obj.optJSONArray("stagedLocalNames")
            val staged = buildList {
                if (stagedArr != null) {
                    for (i in 0 until stagedArr.length()) {
                        val v = stagedArr.optString(i, "")
                        if (v.isNotBlank()) add(v)
                    }
                }
            }
            return CanSessionSyncRecord(
                sessionId = obj.optString("sessionId", ""),
                sessionDirName = obj.optString("sessionDirName", ""),
                startTimeWallMs = obj.optLong("startTimeWallMs", 0L),
                stopTimeWallMs = if (obj.has("stopTimeWallMs") && !obj.isNull("stopTimeWallMs")) obj.optLong("stopTimeWallMs") else null,
                status = CanSessionSyncStatus.fromStringOrDefault(obj.optString("status", "")),
                clockOffsetMs = if (obj.has("clockOffsetMs") && !obj.isNull("clockOffsetMs")) obj.optLong("clockOffsetMs") else null,
                matchedRemotePaths = matched,
                stagedLocalNames = staged,
                lastAttemptMs = obj.optLong("lastAttemptMs", 0L),
                notes = obj.optString("notes", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Persistent multi-session CAN sync manifest stored at `sessions/can_sync_manifest.json`.
 */
data class CanSyncManifest(
    val version: Int = 1,
    val lastClockOffsetMs: Long? = null,
    val lastClockMeasuredAtMs: Long? = null,
    val sessions: List<CanSessionSyncRecord> = emptyList()
) {
    fun pendingCount(): Int = sessions.count { it.status != CanSessionSyncStatus.COMPLETE }

    fun upsertSession(record: CanSessionSyncRecord): CanSyncManifest {
        val updated = sessions.filterNot { it.sessionDirName == record.sessionDirName } + record
        return copy(sessions = updated.sortedBy { it.startTimeWallMs })
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("version", version)
        if (lastClockOffsetMs != null) obj.put("lastClockOffsetMs", lastClockOffsetMs)
        if (lastClockMeasuredAtMs != null) obj.put("lastClockMeasuredAtMs", lastClockMeasuredAtMs)
        val arr = JSONArray()
        sessions.forEach { arr.put(it.toJson()) }
        obj.put("sessions", arr)
        return obj
    }

    companion object {
        fun fromJson(json: String): CanSyncManifest {
            if (json.isBlank()) return CanSyncManifest()
            return try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("sessions")
                val records = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val rec = CanSessionSyncRecord.fromJson(item)
                            if (rec.sessionDirName.isNotBlank()) add(rec)
                        }
                    }
                }
                CanSyncManifest(
                    version = obj.optInt("version", 1),
                    lastClockOffsetMs = if (obj.has("lastClockOffsetMs") && !obj.isNull("lastClockOffsetMs")) obj.optLong("lastClockOffsetMs") else null,
                    lastClockMeasuredAtMs = if (obj.has("lastClockMeasuredAtMs") && !obj.isNull("lastClockMeasuredAtMs")) obj.optLong("lastClockMeasuredAtMs") else null,
                    sessions = records.sortedBy { it.startTimeWallMs }
                )
            } catch (_: Exception) {
                CanSyncManifest()
            }
        }

        fun load(file: File): CanSyncManifest {
            if (!file.exists() || !file.isFile) return CanSyncManifest()
            return try {
                fromJson(file.readText())
            } catch (_: Exception) {
                CanSyncManifest()
            }
        }

        fun save(file: File, manifest: CanSyncManifest) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(manifest.toJson().toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(manifest.toJson().toString(2))
                tmp.delete()
            }
        }
    }
}

/**
 * Authoritative metadata for a staged MF4 file inside `canedge_pool/`.
 * Eliminates reliance on Android `File.lastModified()`, which reflects phone copy time.
 */
data class PoolIndexEntry(
    val localName: String,
    val remotePath: String,
    val folderId: String,
    val sizeBytes: Long,
    val remoteWrittenEpochSec: Long,
    val headerStartTimeMs: Long = 0L,
    val downloadedAtMs: Long = 0L
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("localName", localName)
        obj.put("remotePath", remotePath)
        obj.put("folderId", folderId)
        obj.put("sizeBytes", sizeBytes)
        obj.put("remoteWrittenEpochSec", remoteWrittenEpochSec)
        obj.put("headerStartTimeMs", headerStartTimeMs)
        obj.put("downloadedAtMs", downloadedAtMs)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): PoolIndexEntry {
            return PoolIndexEntry(
                localName = obj.optString("localName", ""),
                remotePath = obj.optString("remotePath", ""),
                folderId = obj.optString("folderId", ""),
                sizeBytes = obj.optLong("sizeBytes", 0L),
                remoteWrittenEpochSec = obj.optLong("remoteWrittenEpochSec", 0L),
                headerStartTimeMs = obj.optLong("headerStartTimeMs", 0L),
                downloadedAtMs = obj.optLong("downloadedAtMs", 0L)
            )
        }
    }
}

data class PoolIndex(
    val entries: List<PoolIndexEntry> = emptyList()
) {
    fun byLocalName(): Map<String, PoolIndexEntry> = entries.associateBy { it.localName }

    fun upsert(entry: PoolIndexEntry): PoolIndex {
        val filtered = entries.filterNot { it.localName == entry.localName } + entry
        return PoolIndex(filtered.sortedBy { it.localName })
    }

    fun retainOnly(existingLocalNames: Set<String>): PoolIndex {
        return PoolIndex(entries.filter { it.localName in existingLocalNames })
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("version", 1)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        obj.put("entries", arr)
        return obj
    }

    companion object {
        fun fromJson(json: String): PoolIndex {
            if (json.isBlank()) return PoolIndex()
            return try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("entries")
                val list = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val entry = PoolIndexEntry.fromJson(item)
                            if (entry.localName.isNotBlank()) add(entry)
                        }
                    }
                }
                PoolIndex(list.sortedBy { it.localName })
            } catch (_: Exception) {
                PoolIndex()
            }
        }

        fun load(file: File): PoolIndex {
            if (!file.exists() || !file.isFile) return PoolIndex()
            return try {
                fromJson(file.readText())
            } catch (_: Exception) {
                PoolIndex()
            }
        }

        fun save(file: File, index: PoolIndex) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(index.toJson().toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(index.toJson().toString(2))
                tmp.delete()
            }
        }
    }
}
