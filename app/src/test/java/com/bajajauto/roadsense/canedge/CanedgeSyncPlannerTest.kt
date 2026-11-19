package com.bajajauto.roadsense.canedge

import com.bajajauto.roadsense.canedge.ingestion.CanSessionSyncRecord
import com.bajajauto.roadsense.canedge.ingestion.CanSessionSyncStatus
import com.bajajauto.roadsense.canedge.ingestion.CanSyncManifest
import com.bajajauto.roadsense.canedge.ingestion.CanedgeSyncPlanner
import com.bajajauto.roadsense.canedge.ingestion.PoolArtifact
import com.bajajauto.roadsense.canedge.ingestion.PoolIndex
import com.bajajauto.roadsense.canedge.ingestion.PoolIndexEntry
import com.bajajauto.roadsense.canedge.model.CanedgeFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CanedgeSyncPlannerTest {

    private fun chunk(folder: String, seq: Int, writtenEpochSec: Long, sizeBytes: Long = 2_500_000L): CanedgeFile {
        val name = String.format("%08d.MF4", seq)
        return CanedgeFile(
            name = name,
            path = "/LOG/7AC5E17F/$folder/$name",
            isDirectory = false,
            sizeBytes = sizeBytes,
            lastWrittenEpochSec = writtenEpochSec
        )
    }

    @Test
    fun filterFilesNeedingDownloadIsIdempotentAcrossPoolAndSessions() {
        val c1 = chunk("00000117", 1, 1_795_000_060L)
        val c2 = chunk("00000117", 2, 1_795_000_120L)
        val c3 = chunk("00000117", 3, 1_795_000_180L, sizeBytes = 3_000_000L)

        val localCombined = mapOf(
            "00000117_00000001.MF4" to 2_500_000L, // staged in session only
            "00000117_00000002.MF4" to 2_500_000L, // in pool
            "00000117_00000003.MF4" to 1_200_000L  // partial size mismatch -> should retry
        )

        val toDownload = CanedgeSyncPlanner.filterFilesNeedingDownload(listOf(c1, c2, c3), localCombined)
        assertEquals(listOf("00000117_00000003.MF4"), toDownload.map { it.localName })
    }

    @Test
    fun measureClockOffsetTriggersOnlyOnObservedLiveRollover() {
        val t0 = 1_795_000_060_000L
        val t1 = 1_795_000_072_000L
        val midWallMs = (t0 + t1) / 2L // 1_795_000_066_000L
        val trueSkewMs = 32_000L // CANedge RTC lags 32s behind Android clock

        // Historical poll with no change must NOT invent a bogus offset equal to file age
        val oldFiles = listOf(
            chunk("00000115", 1, (t0 / 1000L) - 3600L),
            chunk("00000115", 2, (t0 / 1000L) - 3540L)
        )
        assertNull(
            CanedgeSyncPlanner.measureClockOffsetFromLiveTransition(
                previousFilesByPath = oldFiles.associateBy { it.path },
                currentFiles = oldFiles,
                prevPollWallMs = t0,
                nowPollWallMs = t1
            )
        )

        // Chunk 2 closed 60s ago; now chunk 3 closes/appears between t0 and t1 with close mtime ~= midWallMs - trueSkewMs
        val prev = listOf(chunk("00000117", 2, (midWallMs - 60_000L - trueSkewMs) / 1000L))
        val curr = listOf(
            chunk("00000117", 2, (midWallMs - 60_000L - trueSkewMs) / 1000L),
            chunk("00000117", 3, (midWallMs - trueSkewMs) / 1000L)
        )

        val measured = CanedgeSyncPlanner.measureClockOffsetFromLiveTransition(
            previousFilesByPath = prev.associateBy { it.path },
            currentFiles = curr,
            prevPollWallMs = t0,
            nowPollWallMs = t1
        )
        assertNotNull(measured)
        assertTrue("Expected ~32000ms skew, got $measured", abs(measured!! - trueSkewMs) <= 1_000L)
    }

    @Test
    fun planSessionChunksCompensatesRtcSkewAndFillsSequenceGaps() {
        val skewMs = 32_000L
        val baseWallSec = 1_795_000_000L

        // Drive session runs from base+90s to base+250s in Android wall clock time
        val session = CanSessionSyncRecord(
            sessionId = "session_test",
            sessionDirName = "session_20261118_120000",
            startTimeWallMs = (baseWallSec + 90L) * 1000L,
            stopTimeWallMs = (baseWallSec + 250L) * 1000L
        )

        // CANedge chunks written with -32s RTC clock (lastWrittenEpochSec = chunk close time)
        val remote = listOf(
            chunk("00000117", 1, baseWallSec + 60L - 32L),   // wall interval [? .. +60] -> OUTSIDE
            chunk("00000117", 2, baseWallSec + 120L - 32L),  // wall interval [+60 .. +120] -> MATCH
            chunk("00000117", 3, baseWallSec + 180L - 32L),  // wall interval [+120 .. +180] -> MATCH
            chunk("00000117", 4, baseWallSec + 245L - 32L),  // wall interval [+180 .. +245] -> MATCH (within 10s pad of 250s stop)
            chunk("00000117", 5, baseWallSec + 310L - 32L)   // wall interval [+245 .. +310] -> MATCH (straddles 250s stop!)
        )

        val plan = CanedgeSyncPlanner.planSessionChunks(
            session = session,
            remoteFiles = remote,
            clockOffsetMs = skewMs,
            nowWallMs = (baseWallSec + 320L) * 1000L,
            isSessionCurrentlyRecording = false
        )

        assertEquals(
            listOf("00000002.MF4", "00000003.MF4", "00000004.MF4", "00000005.MF4"),
            plan.matchedRemoteFiles.map { it.name }
        )
        assertFalse(plan.waitingForFinalChunk)
    }

    @Test
    fun waitingFinalChunkDetectsOpenTailRightAfterStop() {
        val baseWallSec = 1_795_000_000L
        val session = CanSessionSyncRecord(
            sessionId = "session_tail",
            sessionDirName = "session_tail",
            startTimeWallMs = (baseWallSec + 10L) * 1000L,
            stopTimeWallMs = (baseWallSec + 100L) * 1000L
        )

        // Chunk 2 closes at +120s, so if we check at +105s only chunk 1 (+60s) has closed so far
        val onlyFirstClosed = listOf(chunk("00000117", 1, baseWallSec + 60L))
        val planEarly = CanedgeSyncPlanner.planSessionChunks(
            session = session,
            remoteFiles = onlyFirstClosed,
            clockOffsetMs = 0L,
            nowWallMs = (baseWallSec + 105L) * 1000L,
            isSessionCurrentlyRecording = false
        )
        assertTrue(planEarly.waitingForFinalChunk)

        // Once chunk 2 closes at +120s, tail is satisfied
        val withTail = onlyFirstClosed + chunk("00000117", 2, baseWallSec + 120L)
        val planReady = CanedgeSyncPlanner.planSessionChunks(
            session = session,
            remoteFiles = withTail,
            clockOffsetMs = 0L,
            nowWallMs = (baseWallSec + 125L) * 1000L,
            isSessionCurrentlyRecording = false
        )
        assertFalse(planReady.waitingForFinalChunk)
        assertEquals(2, planReady.matchedRemoteFiles.size)
    }

    @Test
    fun benchTestWithNoCanTrafficExitsWaitingTailQuickly() {
        val yesterdaySec = 1_794_900_000L
        val sessionStartSec = 1_795_000_000L
        val sessionStopSec = sessionStartSec + 45L

        val oldCardFiles = listOf(chunk("00000117", 40, yesterdaySec))

        // At +5s after Stop, wait briefly once to confirm no late flush
        val at5s = CanedgeSyncPlanner.planSessionChunks(
            session = CanSessionSyncRecord("s_bench", "s_bench", sessionStartSec * 1000L, sessionStopSec * 1000L),
            remoteFiles = oldCardFiles,
            clockOffsetMs = 0L,
            nowWallMs = (sessionStopSec + 5L) * 1000L,
            isSessionCurrentlyRecording = false
        )
        assertTrue(at5s.waitingForFinalChunk)

        // At +9s after Stop (> 8s with newest card file older than sessionStart - 120s), finish cleanly!
        val at9s = CanedgeSyncPlanner.planSessionChunks(
            session = CanSessionSyncRecord("s_bench", "s_bench", sessionStartSec * 1000L, sessionStopSec * 1000L),
            remoteFiles = oldCardFiles,
            clockOffsetMs = 0L,
            nowWallMs = (sessionStopSec + 9L) * 1000L,
            isSessionCurrentlyRecording = false
        )
        assertFalse("Bench test with zero CAN traffic must exit WAITING_FINAL_CHUNK after 8s", at9s.waitingForFinalChunk)
        assertTrue(at9s.matchedRemoteFiles.isEmpty())
    }

    @Test
    fun offlinePoolRescueIsolatesSessionsDespiteSameFileCopyTimestamp() {
        val baseSec = 1_795_000_000L
        val sessionA = CanSessionSyncRecord(
            sessionId = "sessionA",
            sessionDirName = "session_A",
            startTimeWallMs = (baseSec + 10L) * 1000L,
            stopTimeWallMs = (baseSec + 110L) * 1000L
        )
        val sessionB = CanSessionSyncRecord(
            sessionId = "sessionB",
            sessionDirName = "session_B",
            startTimeWallMs = (baseSec + 2000L) * 1000L,
            stopTimeWallMs = (baseSec + 2100L) * 1000L
        )

        val artifacts = listOf(
            PoolArtifact("00000117_00000001.MF4", 2_500_000L, baseSec + 60L, (baseSec) * 1000L),
            PoolArtifact("00000117_00000002.MF4", 2_500_000L, baseSec + 120L, (baseSec) * 1000L),
            PoolArtifact("00000118_00000001.MF4", 2_500_000L, baseSec + 2050L, (baseSec + 1995L) * 1000L),
            PoolArtifact("00000118_00000002.MF4", 2_500_000L, baseSec + 2110L, (baseSec + 1995L) * 1000L)
        )

        val rescueB = CanedgeSyncPlanner.planPoolRescueForSession(sessionB, artifacts, clockOffsetMs = 0L)
        assertEquals(listOf("00000118_00000001.MF4", "00000118_00000002.MF4"), rescueB.map { it.localName })
        val rescueA = CanedgeSyncPlanner.planPoolRescueForSession(sessionA, artifacts, clockOffsetMs = 0L)
        assertEquals(listOf("00000117_00000001.MF4", "00000117_00000002.MF4"), rescueA.map { it.localName })
    }

    @Test
    fun safePruneProtectsPendingSessionsAndRetainsUnstagedCache() {
        val manifest = CanSyncManifest(
            sessions = listOf(
                CanSessionSyncRecord(
                    sessionId = "s_complete",
                    sessionDirName = "s_complete",
                    startTimeWallMs = 1000L,
                    stopTimeWallMs = 2000L,
                    status = CanSessionSyncStatus.COMPLETE,
                    stagedLocalNames = listOf("00000116_00000001.MF4")
                ),
                CanSessionSyncRecord(
                    sessionId = "s_pending",
                    sessionDirName = "s_pending",
                    startTimeWallMs = 3000L,
                    stopTimeWallMs = 4000L,
                    status = CanSessionSyncStatus.WAITING_FINAL_CHUNK,
                    stagedLocalNames = listOf("00000117_00000001.MF4")
                )
            )
        )

        val poolNames = listOf(
            "00000116_00000001.MF4", // complete & staged -> safe to prune from pool
            "00000117_00000001.MF4", // protected by non-COMPLETE session
            "00000117_00000002.MF4"  // unstaged recent pool cache -> keep
        )
        val allStaged = setOf("00000116_00000001.MF4", "00000117_00000001.MF4")

        val toPrune = CanedgeSyncPlanner.selectPoolFilesToPrune(poolNames, manifest, allStaged, maxPoolFiles = 50)
        assertEquals(listOf("00000116_00000001.MF4"), toPrune)
    }

    @Test
    fun idlePreSyncAndPruneMaintainAntiThrashInvariant() {
        // Simulate 10 folders with 138 remote closed files (e.g. 00000108..00000117),
        // matching the exact scenario seen in live ADB logs.
        val remote = buildList {
            for (folder in 108..117) {
                val count = if (folder == 117) 40 else 11
                for (seq in 1..count) {
                    add(chunk(String.format("%08d", folder), seq, 1_795_000_000L + (folder * 1000L) + seq * 60L))
                }
            }
        }

        // Idle pre-sync selects at most 25 newest closed files from newest 2 folders
        val idleCandidates = CanedgeSyncPlanner.selectIdlePoolCandidates(remote, maxFolders = 2, maxFiles = 25)
        assertEquals(25, idleCandidates.size)
        assertTrue(idleCandidates.all { it.folderName == "00000117" || it.folderName == "00000116" })

        // Suppose pool holds 70 older unstaged files + all 25 idleCandidates (= 95 files).
        val poolNames = (remote.take(70) + idleCandidates).map { it.localName }.distinct().sorted()
        val prunePlan = CanedgeSyncPlanner.planPoolPrune(
            poolLocalNamesSortedOldestFirst = poolNames,
            manifest = CanSyncManifest(),
            allStagedLocalNames = emptySet(),
            maxPoolFiles = 50
        )

        val retainedAfterPrune = (poolNames - prunePlan.allToDelete.toSet()).associateWith { 2_500_000L }
        assertEquals(50, retainedAfterPrune.size)

        // Next sync poll runs: idleCandidates needing download MUST be 0 (zero re-download ping-pong!)
        val nextPassToDownload = CanedgeSyncPlanner.filterFilesNeedingDownload(idleCandidates, retainedAfterPrune)
        assertTrue("Expected zero re-downloads after pool prune, got ${nextPassToDownload.size}", nextPassToDownload.isEmpty())
    }

    @Test
    fun manifestAndPoolIndexRoundTripJson() {
        val original = CanSyncManifest(
            lastClockOffsetMs = 32_150L,
            lastClockMeasuredAtMs = 1_795_000_000_000L,
            sessions = listOf(
                CanSessionSyncRecord(
                    sessionId = "session_1",
                    sessionDirName = "session_20261118_100000",
                    startTimeWallMs = 100_000L,
                    stopTimeWallMs = 220_000L,
                    status = CanSessionSyncStatus.PARTIAL,
                    clockOffsetMs = 32_150L,
                    matchedRemotePaths = listOf("/LOG/7AC5E17F/00000117/00000001.MF4"),
                    stagedLocalNames = listOf("00000117_00000001.MF4"),
                    lastAttemptMs = 225_000L,
                    notes = "Waiting tail"
                )
            )
        )
        val decoded = CanSyncManifest.fromJson(original.toJson().toString())
        assertEquals(original, decoded)

        val idx = PoolIndex(
            listOf(
                PoolIndexEntry(
                    localName = "00000117_00000001.MF4",
                    remotePath = "/LOG/7AC5E17F/00000117/00000001.MF4",
                    folderId = "00000117",
                    sizeBytes = 2_516_582L,
                    remoteWrittenEpochSec = 1_795_000_060L,
                    headerStartTimeMs = 1_795_000_000_000L,
                    downloadedAtMs = 1_795_000_065_000L
                )
            )
        )
        assertEquals(idx, PoolIndex.fromJson(idx.toJson().toString()))
    }

    @Test
    fun unverifiedClockOffsetDefersSelectionUntilRolloverOrGraceTimeout() {
        // CANedge2 RTC is 180s behind Android (no internet, no prior offset within 24h)
        val baseUtcSec = 1_795_000_000L
        val session = CanSessionSyncRecord(
            sessionId = "s_unverified",
            sessionDirName = "session_unverified",
            startTimeWallMs = (baseUtcSec + 180L) * 1000L,
            stopTimeWallMs = (baseUtcSec + 300L) * 1000L,
            status = CanSessionSyncStatus.PENDING
        )
        val remote = listOf(
            chunk("00000120", 1, baseUtcSec + 60L),
            chunk("00000120", 2, baseUtcSec + 120L),
            chunk("00000120", 3, baseUtcSec + 180L)
        )

        // 5s after stop with unverified offset=0: must defer (waitingForFinalChunk=true, empty match)
        val deferredPlan = CanedgeSyncPlanner.planSessionChunks(
            session = session,
            remoteFiles = remote,
            clockOffsetMs = 0L,
            nowWallMs = session.stopTimeWallMs!! + 5_000L,
            isSessionCurrentlyRecording = false,
            isClockOffsetVerified = false
        )
        assertTrue(deferredPlan.waitingForFinalChunk)
        assertTrue(deferredPlan.matchedRemoteFiles.isEmpty())

        // Once post-drive rollover measures +180_000ms (offsetVerified=true), all 3 chunks match cleanly
        val calibratedPlan = CanedgeSyncPlanner.planSessionChunks(
            session = session,
            remoteFiles = remote,
            clockOffsetMs = 180_000L,
            nowWallMs = session.stopTimeWallMs!! + 45_000L,
            isSessionCurrentlyRecording = false,
            isClockOffsetVerified = true
        )
        assertFalse(calibratedPlan.waitingForFinalChunk)
        assertEquals(listOf("00000120_00000001.MF4", "00000120_00000002.MF4", "00000120_00000003.MF4"),
            calibratedPlan.matchedRemoteFiles.map { it.localName })

        // After 75s unverified-calibration grace + 90s tail grace, must settle without hanging
        val timedOutPlan = CanedgeSyncPlanner.planSessionChunks(
            session = session,
            remoteFiles = remote,
            clockOffsetMs = 0L,
            nowWallMs = session.stopTimeWallMs!! + 91_000L,
            isSessionCurrentlyRecording = false,
            isClockOffsetVerified = false
        )
        assertFalse(timedOutPlan.waitingForFinalChunk)

        // Bench 0-CAN guard: if newest remote chunk is >12h old, do NOT wait 75s even if unverified (exit in 8s)
        val staleRemote = listOf(chunk("00000117", 40, baseUtcSec - 25L * 3600L))
        val benchExitPlan = CanedgeSyncPlanner.planSessionChunks(
            session = session,
            remoteFiles = staleRemote,
            clockOffsetMs = 0L,
            nowWallMs = session.stopTimeWallMs!! + 9_000L,
            isSessionCurrentlyRecording = false,
            isClockOffsetVerified = false
        )
        assertFalse(benchExitPlan.waitingForFinalChunk)
    }
}
