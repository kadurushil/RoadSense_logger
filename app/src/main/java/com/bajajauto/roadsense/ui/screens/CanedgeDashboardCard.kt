package com.bajajauto.roadsense.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Displays connection state, device ID, resolved IP, planned vs synced ratios with progress bars,
 * post-recording staging telemetry, and unified OneDrive-style file explorer.
 */
@Composable
fun CanedgeDashboardCard(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val connectionState by viewModel.canedgeConnectionState.collectAsState()
    val syncStats by viewModel.canedgeSyncStats.collectAsState()
    val unifiedFiles by viewModel.canedgeUnifiedFiles.collectAsState()
    var showExplorerModal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Master Connection Status Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    is CanedgeConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                    is CanedgeConnectionState.Scanning -> MaterialTheme.colorScheme.tertiaryContainer
                    is CanedgeConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (connectionState is CanedgeConnectionState.Scanning) {
                    val scanProgress = (connectionState as CanedgeConnectionState.Scanning).progress
                    LinearProgressIndicator(
                        progress = { scanProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                }
            }
        }

        // Three-Metric Cockpit Row: TARGET SCOPE, SYNC STATUS, THIS SESSION (No emojis, with clean progress bars)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Card 1: TARGET SCOPE (Planned files in top 5 folders)
            Card(
                modifier = Modifier.weight(1f),
                onClick = { showExplorerModal = true },
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
                        text = "top 5 folders",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Card 2: SYNC STATUS (Planned vs Synced Ratio with Progress Bar)
            Card(
                modifier = Modifier.weight(1.15f),
                onClick = {
                    viewModel.canedgeIngestionManager.refreshLocalFileList()
                    showExplorerModal = true
                },
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
                    Text(
                        text = if (syncStats.targetScopeFiles > 0) {
                            "${syncStats.poolSyncedFiles} / ${syncStats.targetScopeFiles}"
                        } else if (syncStats.poolSyncedFiles > 0) {
                            "${syncStats.poolSyncedFiles} files"
                        } else {
                            "-- / --"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (syncStats.targetScopeFiles > 0 && syncStats.poolSyncedFiles >= syncStats.targetScopeFiles) {
                            Color(0xFF4CAF50)
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )

                    if (syncStats.targetScopeFiles > 0) {
                        val progress = (syncStats.poolSyncedFiles.toFloat() / syncStats.targetScopeFiles.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = if (progress >= 1f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.secondary
                        )
                    }

                    Text(
                        text = when {
                            syncStats.targetScopeFiles > 0 && syncStats.poolSyncedFiles >= syncStats.targetScopeFiles -> "All up to date"
                            syncStats.isSyncing -> "Syncing to pool..."
                            syncStats.targetScopeFiles > 0 -> "${syncStats.targetScopeFiles - syncStats.poolSyncedFiles} pending"
                            else -> "in local pool"
                        },
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Card 3: THIS SESSION (Files Staged with Post-Recording Finalizing Progress Bar)
            Card(
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.canedgeIngestionManager.refreshLocalFileList()
                    showExplorerModal = true
                },
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

                    Text(
                        text = if (syncStats.sessionTargetFiles > 0) {
                            "${syncStats.currentSessionFiles} / ${syncStats.sessionTargetFiles}"
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
                        val sessionProgress = (syncStats.currentSessionFiles.toFloat() / syncStats.sessionTargetFiles.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { sessionProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = if (sessionProgress >= 1f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = when {
                            syncStats.isFinalizingSession -> "Finalizing drive..."
                            syncStats.sessionTargetFiles > 0 && syncStats.currentSessionFiles >= syncStats.sessionTargetFiles -> "Staging complete"
                            syncStats.currentSessionFiles > 0 -> "staged to drive"
                            else -> "idle / waiting"
                        },
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Render Unified File Explorer Dialog
        if (showExplorerModal) {
            CanedgeUnifiedFileExplorerDialog(
                unifiedFiles = unifiedFiles,
                stats = syncStats,
                onSyncClick = { viewModel.triggerCanedgeSync() },
                onPruneClick = { viewModel.canedgeIngestionManager.pruneStagingPool() },
                onDismiss = { showExplorerModal = false }
            )
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (connectionState is CanedgeConnectionState.Connected) {
                        viewModel.disconnectCanedge()
                    } else {
                        viewModel.startCanedgeDiscovery()
                    }
                },
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
                onClick = { viewModel.triggerCanedgeSync() },
                enabled = connectionState is CanedgeConnectionState.Connected && !syncStats.isSyncing && !syncStats.isFinalizingSession,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                if (syncStats.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSecondary, strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (syncStats.isSyncing) "Syncing..." else "Sync Now",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Hardware / Diagnostics Information Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
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
                DetailRow(label = "Target Scope", value = "Top 5 Session Folders")
                DetailRow(
                    label = "Last Sync Event",
                    value = if (syncStats.lastSyncTimeMs > 0) {
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(syncStats.lastSyncTimeMs))
                    } else "Never"
                )

                if (syncStats.lastError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Notice: ${syncStats.lastError}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
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
 * Unified CANedge File Explorer Dialog:
 * Shows hierarchical folder view with OneDrive-style sync status badges.
 */
@Composable
private fun CanedgeUnifiedFileExplorerDialog(
    unifiedFiles: List<CanedgeUnifiedFileItem>,
    stats: CanedgeSyncStats,
    onSyncClick: () -> Unit,
    onPruneClick: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "CANedge File Explorer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "Scope: ${stats.targetScopeFiles} | Pool: ${stats.poolSyncedFiles} | Session: ${stats.currentSessionFiles}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            if (unifiedFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No files discovered yet. Connect to device and tap Sync.", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                }
            } else {
                val grouped = unifiedFiles.groupBy { it.folderName }.toSortedMap(compareByDescending { it })
                val latestDir = grouped.keys.firstOrNull()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    grouped.forEach { (dirName, filesInDir) ->
                        val isLatest = dirName == latestDir
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isLatest) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$dirName/",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${filesInDir.size} files",
                                            fontSize = 11.sp,
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
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                filesInDir.forEach { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.file.name,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${"%.1f".format(item.file.sizeBytes / 1024f)} KB",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.outline,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        // OneDrive-style Status Column Badge
                                        Surface(
                                            color = when (item.status) {
                                                CanedgeFileStatus.IN_SESSION -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                                                CanedgeFileStatus.IN_POOL -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                                CanedgeFileStatus.SYNCING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                CanedgeFileStatus.REMOTE_ONLY -> MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = when (item.status) {
                                                    CanedgeFileStatus.IN_SESSION -> "IN SESSION"
                                                    CanedgeFileStatus.IN_POOL -> "IN POOL"
                                                    CanedgeFileStatus.SYNCING -> "SYNCING"
                                                    CanedgeFileStatus.REMOTE_ONLY -> "ON DEVICE"
                                                },
                                                color = when (item.status) {
                                                    CanedgeFileStatus.IN_SESSION -> Color(0xFF2E7D32)
                                                    CanedgeFileStatus.IN_POOL -> MaterialTheme.colorScheme.secondary
                                                    CanedgeFileStatus.SYNCING -> MaterialTheme.colorScheme.primary
                                                    CanedgeFileStatus.REMOTE_ONLY -> MaterialTheme.colorScheme.outline
                                                },
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPruneClick) {
                    Text("Prune Pool", fontSize = 11.sp)
                }
                TextButton(onClick = onSyncClick) {
                    Text("Sync Now", fontSize = 11.sp)
                }
            }
        }
    )
}
