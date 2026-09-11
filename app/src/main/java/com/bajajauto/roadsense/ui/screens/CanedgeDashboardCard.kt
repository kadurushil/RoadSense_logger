package com.bajajauto.roadsense.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
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
import com.bajajauto.roadsense.ui.RadarViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated CANedge2 Hardware Dashboard Card:
 * Displays connection state, device ID, resolved IP, remote/local sync stats,
 * and controls for discovery and manual sync triggers.
 */
@Composable
fun CanedgeDashboardCard(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val connectionState by viewModel.canedgeConnectionState.collectAsState()
    val syncStats by viewModel.canedgeSyncStats.collectAsState()

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

        // Dialog / Inspection State
        var activeModal by remember { mutableStateOf<CanedgeInspectModal?>(null) }
        val remoteFiles by viewModel.canedgeRemoteFiles.collectAsState()
        val localSyncedFiles by viewModel.canedgeLocalSyncedFiles.collectAsState()
        val localSessionFiles by viewModel.canedgeLocalSessionFiles.collectAsState()

        // Three-Metric Cockpit Row (On Device, Total Synced, This Session) - Clickable for Inspection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                onClick = { activeModal = CanedgeInspectModal.OnDevice },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "ON DEVICE 🔍", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${syncStats.totalFilesOnDevice}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = "tap to view tree", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.canedgeIngestionManager.refreshLocalFileList()
                    activeModal = CanedgeInspectModal.Synced
                },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "SYNCED 🔍", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${syncStats.totalSyncedFiles}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(text = "tap to view files", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.canedgeIngestionManager.refreshLocalFileList()
                    activeModal = CanedgeInspectModal.Session
                },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "SESSION 🔍", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${syncStats.currentSessionFiles}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (syncStats.currentSessionFiles > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = "tap to view session", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // Render Inspector Dialog
        when (activeModal) {
            CanedgeInspectModal.OnDevice -> {
                OnDeviceFilesDialog(
                    remoteFiles = remoteFiles,
                    onDismiss = { activeModal = null }
                )
            }
            CanedgeInspectModal.Synced -> {
                LocalFilesDialog(
                    title = "Locally Cached CAN Files (${localSyncedFiles.size})",
                    subtitle = "Stored in local RoadSense storage pool",
                    files = localSyncedFiles,
                    onDismiss = { activeModal = null }
                )
            }
            CanedgeInspectModal.Session -> {
                LocalFilesDialog(
                    title = "Current Session CAN Files (${localSessionFiles.size})",
                    subtitle = "Recorded in active session's 'can/' folder",
                    files = localSessionFiles,
                    onDismiss = { activeModal = null }
                )
            }
            null -> Unit
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
                enabled = connectionState is CanedgeConnectionState.Connected && !syncStats.isSyncing,
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
                DetailRow(label = "Logging Mode", value = "Cyclic 10s Split (MF4)")
                DetailRow(label = "CAN Physical Mode", value = "CAN1 @ 500 kbit/s (RX)")
                DetailRow(
                    label = "Last Sync Event",
                    value = if (syncStats.lastSyncTimeMs > 0) {
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(syncStats.lastSyncTimeMs))
                    } else "Never"
                )

                if (syncStats.lastError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Last Sync Notice: ${syncStats.lastError}",
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
 * Modal states for metric card inspection.
 */
private enum class CanedgeInspectModal {
    OnDevice,
    Synced,
    Session
}

@Composable
private fun OnDeviceFilesDialog(
    remoteFiles: List<com.bajajauto.roadsense.canedge.model.CanedgeFile>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "CANedge Remote SD Card Files (${remoteFiles.size})", style = MaterialTheme.typography.titleMedium)
                Text(text = "Grouped by session directory under /LOG/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        text = {
            if (remoteFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No files discovered yet. Connect to device and tap Sync.", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                }
            } else {
                // Group by parent folder name (e.g. 00000041, 00000040)
                val grouped = remoteFiles.groupBy { file ->
                    val parts = file.path.trim('/').split('/')
                    if (parts.size >= 2) parts[parts.size - 2] else "ROOT"
                }.toSortedMap(compareByDescending { it })

                val latestDir = grouped.keys.firstOrNull()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    grouped.forEach { (dirName, filesInDir) ->
                        val isLatest = dirName == latestDir
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isLatest) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📁 $dirName/",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isLatest) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = "LATEST / ACTIVE",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                filesInDir.forEach { file ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "📄 ${file.name}",
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "${"%.1f".format(file.sizeBytes / 1024f)} KB",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.outline,
                                            fontFamily = FontFamily.Monospace
                                        )
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
        }
    )
}

@Composable
private fun LocalFilesDialog(
    title: String,
    subtitle: String,
    files: List<java.io.File>,
    onDismiss: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        text = {
            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No local files available in this storage directory.", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    files.forEach { file ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = sdf.format(Date(file.lastModified())),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Text(
                                    text = "${"%.1f".format(file.length() / 1024f)} KB",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
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
        }
    )
}
