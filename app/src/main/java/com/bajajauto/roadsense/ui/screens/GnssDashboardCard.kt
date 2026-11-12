package com.bajajauto.roadsense.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.gnss.GnssFix
import com.bajajauto.roadsense.gnss.GnssState
import com.bajajauto.roadsense.ui.RadarViewModel
import java.util.Locale

/**
 * GNSS Navigation Cockpit:
 * Large digital speedometer, compass bearing, satellite health meters,
 * WGS84 coordinates, and independent location switch.
 */
@Composable
fun GnssDashboardCard(
    viewModel: RadarViewModel,
    modifier: Modifier = Modifier,
    gnssState: GnssState? = null,
    latestFix: GnssFix? = null,
    totalFixes: Long? = null
) {
    val activeGnssState = gnssState ?: viewModel.gnssState.collectAsState().value
    val activeLatestFix = latestFix ?: viewModel.latestGnssFix.collectAsState().value
    val activeTotalFixes = totalFixes ?: viewModel.totalGnssFixes.collectAsState().value

    var hasLocationPermission by remember { mutableStateOf(viewModel.hasLocationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasLocationPermission) {
            viewModel.startGnssUpdates()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "switch") {
        // Master GNSS Switch Card
        Card(
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (activeGnssState) {
                    is GnssState.Active -> MaterialTheme.colorScheme.primaryContainer
                    is GnssState.Searching -> MaterialTheme.colorScheme.tertiaryContainer
                    is GnssState.Error -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "GNSS / GPS Navigation Engine",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (activeGnssState) {
                            is GnssState.Disabled -> "Provider Offline"
                            is GnssState.Searching -> "Acquiring Satellites..."
                            is GnssState.Active -> "Locked: $activeTotalFixes total fixes logged"
                            is GnssState.Error -> activeGnssState.message
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Switch(
                    checked = activeGnssState !is GnssState.Disabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            if (!hasLocationPermission) {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            } else {
                                viewModel.startGnssUpdates()
                            }
                        } else {
                            viewModel.stopGnssUpdates()
                        }
                    }
                )
            }
        }

        }
        item(key = "speedometer") {
        Card(
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GROUND SPEED",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = activeLatestFix?.let { "%.1f".format(Locale.US, it.speedKmh) } ?: "--.-",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "km/h",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "ACCURACY", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(
                            text = activeLatestFix?.let { "±%.1f m".format(Locale.US, it.accuracyMeters) } ?: "--",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "ALTITUDE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(
                            text = activeLatestFix?.let { "%.1f m".format(Locale.US, it.altitudeMeters) } ?: "--",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "BEARING", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(
                            text = activeLatestFix?.let { "%.0f°".format(Locale.US, it.bearingDegrees) } ?: "--",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        }
        item(key = "constellation") {
        Card(
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Satellite Constellation & Lock Health",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Satellites Used: ${activeLatestFix?.satellitesUsed ?: 0}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Satellites in View: ${activeLatestFix?.satellitesInView ?: 0}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Progress ratio bar for satellites used vs visible
                val ratio = if ((activeLatestFix?.satellitesInView ?: 0) > 0) {
                    (activeLatestFix?.satellitesUsed ?: 0).toFloat() / (activeLatestFix?.satellitesInView ?: 1)
                } else 0f
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
            }
        }

        }
        item(key = "wgs84") {
        Card(
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "WGS84 Coordinates",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Latitude:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = activeLatestFix?.let { "%.7f°".format(Locale.US, it.latitude) } ?: "--.-------°",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Longitude:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = activeLatestFix?.let { "%.7f°".format(Locale.US, it.longitude) } ?: "--.-------°",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Hardware Monotonic Time:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(
                        text = activeLatestFix?.let { "${it.elapsedRealtimeNs} ns" } ?: "--",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
        }
    }
}
