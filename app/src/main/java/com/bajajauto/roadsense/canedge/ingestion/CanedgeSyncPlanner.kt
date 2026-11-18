package com.bajajauto.roadsense.canedge.ingestion

import com.bajajauto.roadsense.canedge.model.CanedgeFile

/**
 * Parsed `<folderId>_<seq>.MF4` identifier for contiguous sequence-gap checks.
 */
data class ChunkKey(
    val folderId: String,
    val sequence: Long
) : Comparable<ChunkKey> {
    override fun compareTo(other: ChunkKey): Int {
        val folderCmp = folderId.compareTo(other.folderId)
        return if (folderCmp != 0) folderCmp else sequence.compareTo(other.sequence)
    }
}

/**
 * Local artifact summary used for offline pool-to-session rescue without touching `File.lastModified()`.
 */
data class PoolArtifact(
    val localName: String,
    val sizeBytes: Long,
    val remoteWrittenEpochSec: Long,
    val headerStartTimeMs: Long
)

/**
 * Planned session reconciliation output from [CanedgeSyncPlanner.planSessionChunks].
 */
data class SessionSyncPlan(
    val matchedRemoteFiles: List<CanedgeFile>,
    val waitingForFinalChunk: Boolean,
    val estimatedMaxChunkEndWallMs: Long?
)

/**
 * Pure-Kotlin planning logic for idempotent CANedge2 downloads, clock-offset measurement,
 * session chunk matching (including contiguous sequence-gap fill & tail-chunk waiting), and
 * manifest-safe pool pruning.
 */
object CanedgeSyncPlanner {
    private val REMOTE_OR_LOCAL_CHUNK_REGEX = Regex("""^(?:/?([0-9A-Za-z]+)[/_])?([0-9]+)\.MF4$""", RegexOption.IGNORE_CASE)

    private const val CHUNK_DEFAULT_DURATION_MS = 65_000L
    private const val WINDOW_BOUNDARY_PAD_MS = 10_000L
    private const val TAIL_FINALIZE_GRACE_MS = 90_000L
    private const val LIVE_ROLLOVER_MAX_AGE_MS = 25_000L
    private const val MAX_REASONABLE_OFFSET_MS = 12 * 3_600_000L

    fun parseChunkKey(pathOrName: String, fallbackFolder: String = ""): ChunkKey? {
        val cleaned = pathOrName.trim()
        val match = REMOTE_OR_LOCAL_CHUNK_REGEX.matchEntire(cleaned) ?: return null
        val folder = match.groupValues[1].ifBlank { fallbackFolder }
        val seq = match.groupValues[2].toLongOrNull() ?: return null
        return ChunkKey(folderId = folder, sequence = seq)
    }

    /**
     * Filter out any remote [CanedgeFile] that already exists locally with matching byte size in
     * either `canedge_pool/` OR any `sessions/<session>/can/` directory.
     */
    fun filterFilesNeedingDownload(
        candidates: List<CanedgeFile>,
        existingLocalSizesByName: Map<String, Long>
    ): List<CanedgeFile> {
        return candidates.filter { remote ->
            if (!remote.isMf4 || remote.sizeBytes <= 0L) return@filter false
            val existingSize = existingLocalSizesByName[remote.localName]
            existingSize == null || existingSize != remote.sizeBytes
        }
    }

    /**
     * Estimate `clockOffsetMs = phoneWallMs - canedgeEpochMs` strictly from a live chunk rollover
     * observed between two consecutive polls `[prevPollWallMs, nowPollWallMs]`.
     *
     * Never infers clock offset from arbitrary historical files (which would confuse recording age
     * with RTC skew). Returns null if no fresh rollover occurred during this poll interval.
     */
    fun measureClockOffsetFromLiveTransition(
        previousFilesByPath: Map<String, CanedgeFile>,
        currentFiles: List<CanedgeFile>,
        prevPollWallMs: Long,
        nowPollWallMs: Long
    ): Long? {
        if (previousFilesByPath.isEmpty() || currentFiles.isEmpty()) return null
        val pollDelta = nowPollWallMs - prevPollWallMs
        if (pollDelta !in 1L..LIVE_ROLLOVER_MAX_AGE_MS) return null

        val prevByFolder = previousFilesByPath.values.filter { it.isMf4 && it.sizeBytes > 0L }.groupBy { it.folderName }
        val currByFolder = currentFiles.filter { it.isMf4 && it.sizeBytes > 0L }.groupBy { it.folderName }

        // Case 1: New chunk (seq + 1) just appeared in the newest folder -> the prior highest chunk
        // closed inside [prevPollWallMs, nowPollWallMs].
        val newestFolder = currByFolder.keys.maxOrNull() ?: return null
        val currInFolder = currByFolder[newestFolder].orEmpty().sortedBy { it.name }
        val prevInFolder = prevByFolder[newestFolder].orEmpty().sortedBy { it.name }

        val midWallMs = (prevPollWallMs + nowPollWallMs) / 2L

        if (prevInFolder.isNotEmpty() && currInFolder.size > prevInFolder.size) {
            val prevLast = prevInFolder.last()
            val currMatchOfPrevLast = currInFolder.firstOrNull { it.path == prevLast.path }
            if (currMatchOfPrevLast != null && currMatchOfPrevLast.lastWrittenEpochSec > 0L) {
                val offset = midWallMs - currMatchOfPrevLast.lastWrittenMs
                if (kotlin.math.abs(offset) <= MAX_REASONABLE_OFFSET_MS) return offset
            }
        }

        // Case 2: Brand-new session folder started on CANedge between polls -> the initial file's
        // creation/first flush happened right inside [prevPollWallMs, nowPollWallMs].
        val prevMaxFolder = prevByFolder.keys.maxOrNull()
        if (prevMaxFolder != null && newestFolder > prevMaxFolder) {
            val newestFirst = currInFolder.firstOrNull()
            if (newestFirst != null && newestFirst.lastWrittenEpochSec > 0L) {
                val offset = midWallMs - newestFirst.lastWrittenMs
                if (kotlin.math.abs(offset) <= MAX_REASONABLE_OFFSET_MS) return offset
            }
        }

        return null
    }

    /**
     * Plan which remote [CanedgeFile] chunks belong to [session], accounting for:
     * - Chunk intervals `[chunkStartWall, chunkEndWall]` derived from predecessor close timestamp or MDF4 HDBLOCK start + [clockOffsetMs]
     * - Contiguous sequence gap fill within any matched folder (`minSeq..maxSeq`)
     * - Post-stop tail detection (`waitingForFinalChunk`) while CANedge finishes writing the current 60s split
     */
    fun planSessionChunks(
        session: CanSessionSyncRecord,
        remoteFiles: List<CanedgeFile>,
        clockOffsetMs: Long,
        nowWallMs: Long,
        isSessionCurrentlyRecording: Boolean,
        headerStartTimeByLocalName: Map<String, Long> = emptyMap()
    ): SessionSyncPlan {
        val mf4s = remoteFiles.filter { it.isMf4 && it.sizeBytes > 0L }
        if (mf4s.isEmpty()) {
            val waiting = !isSessionCurrentlyRecording &&
                session.stopTimeWallMs != null &&
                (nowWallMs - session.stopTimeWallMs) < TAIL_FINALIZE_GRACE_MS
            return SessionSyncPlan(
                matchedRemoteFiles = emptyList(),
                waitingForFinalChunk = waiting,
                estimatedMaxChunkEndWallMs = null
            )
        }

        val effectiveStopMs = session.stopTimeWallMs ?: nowWallMs
        val windowStart = session.startTimeWallMs - WINDOW_BOUNDARY_PAD_MS
        val windowEnd = effectiveStopMs + WINDOW_BOUNDARY_PAD_MS

        val sorted = mf4s.sortedWith(compareBy({ it.folderName }, { parseChunkKey(it.path)?.sequence ?: 0L }, { it.name }))
        val byFolder = sorted.groupBy { it.folderName }

        data class TimedChunk(val file: CanedgeFile, val seq: Long?, val startWallMs: Long, val endWallMs: Long)

        val timedByFolder = byFolder.mapValues { (_, list) ->
            list.mapIndexed { idx, file ->
                val seq = parseChunkKey(file.path, file.folderName)?.sequence
                val endCanMs = file.lastWrittenMs
                val startCanMs = when {
                    idx > 0 && list[idx - 1].lastWrittenEpochSec > 0L -> list[idx - 1].lastWrittenMs
                    else -> {
                        val hdr = headerStartTimeByLocalName[file.localName] ?: 0L
                        if (hdr > 0L && hdr <= endCanMs) hdr else (endCanMs - CHUNK_DEFAULT_DURATION_MS)
                    }
                }
                TimedChunk(
                    file = file,
                    seq = seq,
                    startWallMs = startCanMs + clockOffsetMs,
                    endWallMs = endCanMs + clockOffsetMs
                )
            }
        }

        val selected = mutableListOf<TimedChunk>()
        for ((_, chunks) in timedByFolder) {
            val overlapping = chunks.filter { c ->
                c.endWallMs >= windowStart && c.startWallMs <= windowEnd
            }
            if (overlapping.isEmpty()) continue

            val seqs = overlapping.mapNotNull { it.seq }
            if (seqs.isNotEmpty()) {
                val minSeq = seqs.min()
                val maxSeq = seqs.max()
                val gapFilled = chunks.filter { c ->
                    (c.seq != null && c.seq in minSeq..maxSeq) || c in overlapping
                }
                selected.addAll(gapFilled)
            } else {
                selected.addAll(overlapping)
            }
        }

        val distinctSorted = selected
            .distinctBy { it.file.path }
            .sortedWith(compareBy({ it.file.folderName }, { it.seq ?: 0L }, { it.file.name }))

        val maxEndWallMs = distinctSorted.maxOfOrNull { it.endWallMs }
        val newestRemoteEndWallMs = sorted.maxOf { it.lastWrittenMs + clockOffsetMs }

        // Distinguish active drive logging (wait up to 90s for CANedge's 60s split chunk to close)
        // from an idle/bench CANedge with no CAN bus traffic at all (settle after 8s confirm check).
        val waitingForFinalChunk = if (!isSessionCurrentlyRecording && session.stopTimeWallMs != null) {
            val sinceStopMs = nowWallMs - session.stopTimeWallMs
            val noRemoteActivityNearDrive = newestRemoteEndWallMs < (session.startTimeWallMs - 120_000L)
            val effectiveGraceMs = when {
                distinctSorted.isEmpty() && noRemoteActivityNearDrive -> 8_000L
                distinctSorted.isEmpty() -> 65_000L
                else -> TAIL_FINALIZE_GRACE_MS
            }
            val withinGrace = sinceStopMs < effectiveGraceMs
            val missingTailTime = maxEndWallMs == null || maxEndWallMs < (session.stopTimeWallMs - 2_000L)
            withinGrace && missingTailTime
        } else {
            false
        }

        return SessionSyncPlan(
            matchedRemoteFiles = distinctSorted.map { it.file },
            waitingForFinalChunk = waitingForFinalChunk,
            estimatedMaxChunkEndWallMs = maxEndWallMs
        )
    }

    /**
     * Offline fallback matcher using ONLY local pool artifacts ([PoolIndexEntry] + [Mdf4HeaderInspector]),
     * completely avoiding unreliable Android `File.lastModified()`.
     */
    fun planPoolRescueForSession(
        session: CanSessionSyncRecord,
        artifacts: List<PoolArtifact>,
        clockOffsetMs: Long
    ): List<PoolArtifact> {
        if (artifacts.isEmpty() || session.stopTimeWallMs == null) return emptyList()

        val windowStart = session.startTimeWallMs - WINDOW_BOUNDARY_PAD_MS
        val windowEnd = session.stopTimeWallMs + WINDOW_BOUNDARY_PAD_MS

        val sorted = artifacts.sortedWith(compareBy({ parseChunkKey(it.localName)?.folderId ?: "" }, { parseChunkKey(it.localName)?.sequence ?: 0L }, { it.localName }))
        val byFolder = sorted.groupBy { parseChunkKey(it.localName)?.folderId ?: "" }

        val matched = mutableListOf<PoolArtifact>()
        for ((_, list) in byFolder) {
            val timed = list.mapIndexed { idx, a ->
                val endCanMs = when {
                    a.remoteWrittenEpochSec > 0L -> a.remoteWrittenEpochSec * 1000L
                    a.headerStartTimeMs > 0L -> a.headerStartTimeMs + CHUNK_DEFAULT_DURATION_MS
                    else -> 0L
                }
                val startCanMs = when {
                    idx > 0 && list[idx - 1].remoteWrittenEpochSec > 0L -> list[idx - 1].remoteWrittenEpochSec * 1000L
                    a.headerStartTimeMs > 0L -> a.headerStartTimeMs
                    endCanMs > 0L -> endCanMs - CHUNK_DEFAULT_DURATION_MS
                    else -> 0L
                }
                Triple(a, startCanMs + clockOffsetMs, endCanMs + clockOffsetMs)
            }
            val overlapping = timed.filter { (a, startWall, endWall) ->
                (a.remoteWrittenEpochSec > 0L || a.headerStartTimeMs > 0L) &&
                    endWall >= windowStart && startWall <= windowEnd
            }
            if (overlapping.isEmpty()) continue
            val seqs = overlapping.mapNotNull { parseChunkKey(it.first.localName)?.sequence }
            if (seqs.isNotEmpty()) {
                val minSeq = seqs.min()
                val maxSeq = seqs.max()
                matched.addAll(
                    timed.filter { (a, _, _) ->
                        val s = parseChunkKey(a.localName)?.sequence
                        (s != null && s in minSeq..maxSeq) || overlapping.any { it.first.localName == a.localName }
                    }.map { it.first }
                )
            } else {
                matched.addAll(overlapping.map { it.first })
            }
        }
        return matched.distinctBy { it.localName }
    }

    /**
     * Breakdown of `canedge_pool/` pruning candidates for diagnostics and verification.
     */
    data class PoolPrunePlan(
        val stagedAndCompleteToDelete: List<String>,
        val overflowToDelete: List<String>,
        val protectedNames: Set<String>
    ) {
        val allToDelete: List<String> get() = stagedAndCompleteToDelete + overflowToDelete
    }

    /**
     * Select at most [maxFiles] newest closed files from the newest [maxFolders] folders
     * for idle background pre-sync.
     *
     * Keeping `maxFiles < pruneMaxPoolFiles` guarantees an anti-thrash invariant: files evicted
     * from the bottom of `canedge_pool/` by the pool size cap are never in the idle pre-sync
     * download set, preventing infinite download -> prune -> re-download loops.
     */
    fun selectIdlePoolCandidates(
        stableRemoteFiles: List<CanedgeFile>,
        maxFolders: Int = 2,
        maxFiles: Int = 25
    ): List<CanedgeFile> {
        if (stableRemoteFiles.isEmpty() || maxFolders <= 0 || maxFiles <= 0) return emptyList()
        val allowedFolders = stableRemoteFiles
            .asSequence()
            .filter { it.isMf4 && it.sizeBytes > 0L }
            .map { it.folderName }
            .distinct()
            .sortedDescending()
            .take(maxFolders)
            .toSet()

        return stableRemoteFiles
            .asSequence()
            .filter { it.isMf4 && it.sizeBytes > 0L && it.folderName in allowedFolders }
            .sortedWith(
                compareByDescending<CanedgeFile> { it.folderName }
                    .thenByDescending { parseChunkKey(it.path, it.folderName)?.sequence ?: 0L }
                    .thenByDescending { it.name }
            )
            .take(maxFiles)
            .toList()
    }

    /**
     * Compute both staged-complete eviction candidates and hard-cap overflow candidates.
     */
    fun planPoolPrune(
        poolLocalNamesSortedOldestFirst: List<String>,
        manifest: CanSyncManifest,
        allStagedLocalNames: Set<String>,
        maxPoolFiles: Int = 50
    ): PoolPrunePlan {
        val protectedNames = manifest.sessions
            .filter { it.status != CanSessionSyncStatus.COMPLETE }
            .flatMap { it.stagedLocalNames }
            .toSet()

        val stagedCompleteToDelete = mutableListOf<String>()
        val overflowToDelete = mutableListOf<String>()
        val remaining = poolLocalNamesSortedOldestFirst.toMutableList()

        // Pass 1: Safely evict files already staged into finalized (COMPLETE) sessions
        // and not needed by any pending/partial/waiting session.
        for (name in poolLocalNamesSortedOldestFirst) {
            if (name in allStagedLocalNames && name !in protectedNames) {
                stagedCompleteToDelete.add(name)
                remaining.remove(name)
            }
        }

        // Pass 2: Enforce hard pool cap on oldest unstaged files beyond maxPoolFiles
        while (remaining.size > maxPoolFiles) {
            val candidate = remaining.firstOrNull { it !in protectedNames } ?: break
            overflowToDelete.add(candidate)
            remaining.remove(candidate)
        }

        return PoolPrunePlan(
            stagedAndCompleteToDelete = stagedCompleteToDelete,
            overflowToDelete = overflowToDelete,
            protectedNames = protectedNames
        )
    }

    /**
     * Decide which filenames in `canedge_pool/` may safely be pruned.
     */
    fun selectPoolFilesToPrune(
        poolLocalNamesSortedOldestFirst: List<String>,
        manifest: CanSyncManifest,
        allStagedLocalNames: Set<String>,
        maxPoolFiles: Int = 50
    ): List<String> = planPoolPrune(
        poolLocalNamesSortedOldestFirst = poolLocalNamesSortedOldestFirst,
        manifest = manifest,
        allStagedLocalNames = allStagedLocalNames,
        maxPoolFiles = maxPoolFiles
    ).allToDelete
}
