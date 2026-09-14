package com.bajajauto.roadsense.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bajajauto.roadsense.canedge.model.CanedgeConnectionState
import com.bajajauto.roadsense.canedge.model.CanedgeFileStatus
import com.bajajauto.roadsense.canedge.model.CanedgeSyncStats
import com.bajajauto.roadsense.canedge.model.CanedgeUnifiedFileItem
import com.bajajauto.roadsense.ui.RadarViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated CANedge2 Hardware Dashboard Card:
 * Features an adaptive dual-pane layout in landscape for vehicle mounts,
 * real-time transfer telemetry monitor (active file, speed KB/s, ETA, progress),
 * target scope metrics for the 2 recent folders, and unified file explorer.
 */
@Composable
fun CanedgeDashboardCard(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.canedgeConnectionState.collectAsState()
    val syncStats by viewModel.canedgeSyncStats.collectAsState()
    val unifiedFiles by viewModel.canedgeUnifiedFiles.collectAsState()
    var showExplorerModal by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // Landscape Dual-Pane Cockpit Layout
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Pane: Hardware Status, Control Actions, Diagnostics
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CanedgeConnectionStatusCard(connectionState = connectionState)

                CanedgeControlActions(
                    connectionState = connectionState,
                    syncStats = syncStats,
                    onDiscoverDisconnect = {
                        if (connectionState is CanedgeConnectionState.Connected) {
                            viewModel.disconnectCanedge()
                        } else {
                            viewModel.startCanedgeDiscovery()
                        }
                    },
                    onSync = { viewModel.triggerCanedgeSync() },
                    onOpenExplorer = {
                        viewModel.canedgeIngestionManager.refreshLocalFileList()
                        showExplorerModal = true
                    },
                    onPrunePool = { viewModel.canedgeIngestionManager.pruneStagingPool() }
                )

                CanedgeDiagnosticsCard(
                    connectionState = connectionState,
                    syncStats = syncStats
                )
            }

            // Right Pane: Live Transfer Monitor Console & Three-Metric Cockpit Cards
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CanedgeLiveTransferCard(stats = syncStats)

                CanedgeMetricsRow(
                    syncStats = syncStats,
                    onCardClick = {
                        viewModel.canedgeIngestionManager.refreshLocalFileList()
                        showExplorerModal = true
                    }
                )

                if (syncStats.lastError != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Notice: ${syncStats.lastError}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Portrait Single-Column Layout
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CanedgeConnectionStatusCard(connectionState = connectionState)

            CanedgeMetricsRow(
                syncStats = syncStats,
                onCardClick = {
                    viewModel.canedgeIngestionManager.refreshLocalFileList()
                    showExplorerModal = true
                }
            )

            CanedgeLiveTransferCard(stats = syncStats)

            CanedgeControlActions(
                connectionState = connectionState,
                syncStats = syncStats,
                onDiscoverDisconnect = {
                    if (connectionState is CanedgeConnectionState.Connected) {
                        viewModel.disconnectCanedge()
                    } else {
                        viewModel.startCanedgeDiscovery()
                    }
                },
                onSync = { viewModel.triggerCanedgeSync() },
                onOpenExplorer = {
                    viewModel.canedgeIngestionManager.refreshLocalFileList()
                    showExplorerModal = true
                },
                onPrunePool = { viewModel.canedgeIngestionManager.pruneStagingPool() }
            )

            CanedgeDiagnosticsCard(
                connectionState = connectionState,
                syncStats = syncStats
            )
        }
    }

    // Unified File Explorer Dialog
    if (showExplorerModal) {
        CanedgeUnifiedFileExplorerDialog(
            unifiedFiles = unifiedFiles,
            stats = syncStats,
            onSyncClick = { viewModel.triggerCanedgeSync() },
            onPruneClick = { viewModel.canedgeIngestionManager.pruneStagingPool() },
            onDismiss = { showExplorerModal = false }
        )
    }
}

/**
 * Master Connection Status Card displaying device status, online IP, or discovery progress.
 */
@Composable
private fun CanedgeConnectionStatusCard(
    connectionState: CanedgeConnectionState,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                is CanedgeConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                is CanedgeConnectionState.Scanning -> MaterialTheme.colorScheme.tertiaryContainer
                is CanedgeConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (connectionState) {
                            is CanedgeConnectionState.Connected -> Icons.Default.Wifi
                            else -> Icons.Default.WifiOff
                        },
                        contentDescription = null,
                        tint = when (connectionState) {
                            is CanedgeConnectionState.Connected -> MaterialTheme.colorScheme.primary
                            is CanedgeConnectionState.Error -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Column {
                        Text(
                            text = "CSS Electronics CANedge2",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (val state = connectionState) {
                                is CanedgeConnectionState.Connected -> "Online: ${state.device.deviceId} (${state.device.ipAddress})"
                                is CanedgeConnectionState.Scanning -> state.message
                                is CanedgeConnectionState.Error -> "Error: ${state.message}"
                                is CanedgeConnectionState.Disconnected -> "Offline (Tap Discover to connect)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (connectionState is CanedgeConnectionState.Scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            if (connectionState is CanedgeConnectionState.Scanning) {
                val scanProgress = (connectionState as CanedgeConnectionState.Scanning).progress
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }
        }
    }
}

/**
 * Real-Time Transfer Telemetry Monitor:
 * Displays active file name, progress bar, transfer speed (KB/s or MB/s), bytes transferred, and ETA.
 */
@Composable
fun CanedgeLiveTransferCard(
    stats: CanedgeSyncStats,
    modifier: Modifier = Modifier
) {
    val isTransferActive = stats.isSyncing || stats.isFinalizingSession || stats.activeFileName != null

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isTransferActive) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Status Title and Mode Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isTransferActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = if (stats.isFinalizingSession) "SESSION CAN STAGING" else "CANEDGE TRANSFER CONSOLE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = if (isTransferActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }

                if (isTransferActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = if (stats.isFinalizingSession) "STAGING" else "SYNCING",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // File Name & Percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stats.activeFileName ?: if (isTransferActive) "Preparing transfer..." else "Idle / Ready",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isTransferActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )

                if (isTransferActive && stats.activeFileProgress > 0f) {
                    Text(
                        text = "${(stats.activeFileProgress * 100).toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Linear Progress Bar
            if (isTransferActive) {
                if (stats.activeFileTotalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { stats.activeFileProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Telemetry Stats Row: Transferred Size, Live Speed, ETA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Size transferred
                    Text(
                        text = stats.formattedTransferSize.ifEmpty { "-- / --" },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Transfer Speed
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = stats.formattedSpeed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    // ETA
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = if (stats.etaSeconds > 0) "ETA ~${stats.etaSeconds}s" else "ETA --",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                Text(
                    text = if (syncStatsLastSyncText(stats).isNotEmpty()) {
                        syncStatsLastSyncText(stats)
                    } else {
                        "Tap 'Sync Scope' to pull latest 1-min MF4 files from the 2 recent folders."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun syncStatsLastSyncText(stats: CanedgeSyncStats): String {
    return if (stats.lastSyncTimeMs > 0L) {
        "Last synced at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(stats.lastSyncTimeMs))}"
    } else ""
}

/**
 * Three-Metric Cockpit Row: TARGET SCOPE (2 folders), SYNC STATUS, and THIS SESSION.
 */
@Composable
private fun CanedgeMetricsRow(
    syncStats: CanedgeSyncStats,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Card 1: TARGET SCOPE (Planned files in 2 recent folders)
        Card(
            modifier = Modifier.weight(1f),
            onClick = onCardClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "TARGET SCOPE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${syncStats.targetScopeFiles}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "2 recent folders",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // Card 2: SYNC STATUS (Planned vs Synced Ratio with Progress Bar)
        Card(
            modifier = Modifier.weight(1.15f),
            onClick = onCardClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "SYNC STATUS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val safeSynced = syncStats.poolSyncedFiles.coerceAtMost(syncStats.targetScopeFiles)
                Text(
                    text = if (syncStats.targetScopeFiles > 0) {
                        "$safeSynced / ${syncStats.targetScopeFiles}"
                    } else if (syncStats.poolFilesCount > 0) {
                        "${syncStats.poolFilesCount} cached"
                    } else {
                        "-- / --"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (syncStats.targetScopeFiles > 0 && safeSynced >= syncStats.targetScopeFiles) {
                        Color(0xFF4CAF50)
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )

                if (syncStats.targetScopeFiles > 0) {
                    val progress = (safeSynced.toFloat() / syncStats.targetScopeFiles.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = if (progress >= 1f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.secondary
                    )
                }

                Text(
                    text = when {
                        syncStats.targetScopeFiles > 0 && safeSynced >= syncStats.targetScopeFiles -> "All up to date"
                        syncStats.isSyncing -> "Syncing to pool..."
                        syncStats.targetScopeFiles > 0 -> "${syncStats.targetScopeFiles - safeSynced} pending"
                        syncStats.poolFilesCount > 0 -> "cached in pool"
                        else -> "idle / ready"
                    },
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // Card 3: THIS SESSION (Files Staged with Post-Recording Finalizing Progress Bar)
        Card(
            modifier = Modifier.weight(1f),
            onClick = onCardClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "THIS SESSION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val safeSessionSynced = if (syncStats.sessionTargetFiles > 0) syncStats.currentSessionFiles.coerceAtMost(syncStats.sessionTargetFiles) else syncStats.currentSessionFiles
                Text(
                    text = if (syncStats.sessionTargetFiles > 0) {
                        "$safeSessionSynced / ${syncStats.sessionTargetFiles}"
                    } else if (syncStats.currentSessionFiles > 0) {
                        "${syncStats.currentSessionFiles} files"
                    } else {
                        "--"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        syncStats.isFinalizingSession -> MaterialTheme.colorScheme.tertiary
                        syncStats.currentSessionFiles > 0 -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (syncStats.sessionTargetFiles > 0) {
                    val sessionProgress = (safeSessionSynced.toFloat() / syncStats.sessionTargetFiles.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { sessionProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = if (sessionProgress >= 1f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = when {
                        syncStats.isFinalizingSession -> "Finalizing drive..."
                        syncStats.sessionTargetFiles > 0 && safeSessionSynced >= syncStats.sessionTargetFiles -> "Staging complete"
                        syncStats.currentSessionFiles > 0 -> "staged to drive"
                        else -> "idle / waiting"
                    },
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * Primary and Secondary Action Buttons: Discover/Disconnect, Sync, Explorer, and Prune.
 */
@Composable
private fun CanedgeControlActions(
    connectionState: CanedgeConnectionState,
    syncStats: CanedgeSyncStats,
    onDiscoverDisconnect: () -> Unit,
    onSync: () -> Unit,
    onOpenExplorer: () -> Unit,
    onPrunePool: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Main Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onDiscoverDisconnect,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connectionState is CanedgeConnectionState.Connected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (connectionState is CanedgeConnectionState.Connected) "Disconnect" else "Discover Device",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onSync,
                enabled = connectionState is CanedgeConnectionState.Connected && !syncStats.isSyncing && !syncStats.isFinalizingSession,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                if (syncStats.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onSecondary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (syncStats.isSyncing) "Syncing..." else "Sync Scope",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Secondary Utilities Row: Unified Explorer & Prune Staging Pool
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenExplorer,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "File Explorer", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onPrunePool,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Prune Pool", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Diagnostics & Hardware Telemetry Details Card.
 */
@Composable
private fun CanedgeDiagnosticsCard(
    connectionState: CanedgeConnectionState,
    syncStats: CanedgeSyncStats,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "HARDWARE & INGESTION TELEMETRY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            val device = (connectionState as? CanedgeConnectionState.Connected)?.device

            DetailRow(label = "Target Hotspot (AP)", value = "M21 (WPA2, 2.4 GHz)")
            DetailRow(label = "Configured Device ID", value = device?.deviceId ?: "7AC5E17F")
            DetailRow(label = "Resolved IP Address", value = device?.ipAddress ?: "Unassigned")
            DetailRow(label = "Web Server Base URL", value = device?.apiBaseUrl ?: "http://.../api/")
            DetailRow(label = "Staging Pool Directory", value = "sessions/canedge_pool/")
            DetailRow(label = "Target Scope", value = "2 Most Recent Folders")
            DetailRow(label = "Chunk Splitting", value = "1 Minute (60s chunks)")
            DetailRow(
                label = "Last Sync Event",
                value = if (syncStats.lastSyncTimeMs > 0) {
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(syncStats.lastSyncTimeMs))
                } else "Never"
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Decluttered, Clean Nested CANedge File Explorer Dialog:
 * Features a collapsible folder tree with a 3-column location matrix beside each file:
 * 1. CAN: On CANedge hardware logger SD card
 * 2. DEV: Copied to device (in phone staging pool or phone storage)
 * 3. SES: Assigned to session (staged in a drive session folder)
 */
@Composable
private fun CanedgeUnifiedFileExplorerDialog(
    unifiedFiles: List<CanedgeUnifiedFileItem>,
    stats: CanedgeSyncStats,
    onSyncClick: () -> Unit,
    onPruneClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.94f else 0.96f)
                .fillMaxHeight(if (isLandscape) 0.94f else 0.90f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header (Title, Scope stats, Legend)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CANedge File Explorer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "Scope: ${stats.targetScopeFiles} | Synced: ${stats.poolSyncedFiles} | In Session: ${stats.currentSessionFiles}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Compact 3-Column Legend
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LegendItem(
                                icon = Icons.Default.SdStorage,
                                label = "CANedge SD",
                                color = MaterialTheme.colorScheme.primary
                            )
                            LegendItem(
                                icon = Icons.Default.PhoneAndroid,
                                label = "Copied to Phone",
                                color = MaterialTheme.colorScheme.secondary
                            )
                            LegendItem(
                                icon = Icons.Default.CheckCircle,
                                label = "In Session",
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }

                // Column Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FILE NAME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CAN",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = "DEV",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = "SES",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(24.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Scrollable File List (takes ALL remaining space!)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (unifiedFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No files discovered yet. Connect to device and tap Sync.",
                                color = MaterialTheme.colorScheme.outline,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        val grouped = unifiedFiles.groupBy { it.folderName }.toSortedMap(compareByDescending { it })
                        val latestDir = grouped.keys.firstOrNull()
                        var expandedFolders by remember { mutableStateOf(grouped.keys.toSet()) }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            grouped.forEach { (dirName, filesInDir) ->
                                val isLatest = dirName == latestDir
                                val isExpanded = expandedFolders.contains(dirName)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                ) {
                                    // Folder Header (Collapsible Row)
                                    Surface(
                                        color = if (isLatest) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expandedFolders = if (isExpanded) {
                                                    expandedFolders - dirName
                                                } else {
                                                    expandedFolders + dirName
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "$dirName/",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${filesInDir.size} files",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                if (isLatest) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = MaterialTheme.shapes.extraSmall
                                                    ) {
                                                        Text(
                                                            text = "LATEST",
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Nested Files under this Folder
                                    if (isExpanded) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            filesInDir.forEach { item ->
                                                CanedgeFileRow(item = item)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Bottom Action Buttons Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPruneClick) {
                            Text("Prune Pool", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onSyncClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Sync Scope", fontSize = 12.sp)
                        }
                    }
                    Button(onClick = onDismiss) {
                        Text("Close", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Clean, single-line file row with 3 location indicator columns.
 */
@Composable
private fun CanedgeFileRow(
    item: CanedgeUnifiedFileItem,
    modifier: Modifier = Modifier
) {
    val inactiveColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: File icon, Name, and Size
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
            Column {
                Text(
                    text = item.file.name,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${"%.1f".format(item.file.sizeBytes / 1024f)} KB",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Right: 3 Column Status Icons (CANedge, Copied to Device, Assigned to Session)
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Col 1: Logger SD
            Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.SdStorage,
                    contentDescription = "On CANedge",
                    modifier = Modifier.size(16.dp),
                    tint = if (item.isOnCanedge) MaterialTheme.colorScheme.primary else inactiveColor
                )
            }

            // Col 2: Copied to Device (Pool or Session)
            Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                if (item.status == CanedgeFileStatus.SYNCING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "Copied to device",
                        modifier = Modifier.size(16.dp),
                        tint = if (item.isCopiedToDevice) MaterialTheme.colorScheme.secondary else inactiveColor
                    )
                }
            }

            // Col 3: Assigned to Session
            Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Assigned to session",
                    modifier = Modifier.size(16.dp),
                    tint = if (item.isAssignedToSession) Color(0xFF4CAF50) else inactiveColor
                )
            }
        }
    }
}

@Composable
private fun LegendItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = color
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
