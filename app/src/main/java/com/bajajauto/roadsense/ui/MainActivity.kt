package com.bajajauto.roadsense.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bajajauto.roadsense.acquisition.RadarConnectionState
import com.bajajauto.roadsense.ui.theme.RoadSenseTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoadSenseTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RadarTestScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun RadarTestScreen(viewModel: RadarViewModel, modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsState()
    val rawHexData by viewModel.rawHexData.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()
    val totalPackets by viewModel.totalPackets.collectAsState()
    val latestPacket by viewModel.latestPacket.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "RoadSense - Radar Packet Detector",
            style = MaterialTheme.typography.titleLarge
        )

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status: ${
                        when (connectionState) {
                            is RadarConnectionState.Disconnected -> "Disconnected"
                            is RadarConnectionState.Connecting -> "Connecting..."
                            is RadarConnectionState.Connected -> (connectionState as RadarConnectionState.Connected).portInfo
                            is RadarConnectionState.Error -> "Error: ${(connectionState as RadarConnectionState.Error).message}"
                        }
                    }",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Total Bytes: $totalBytes", style = MaterialTheme.typography.bodySmall)
                    Text(text = "Valid Packets: $totalPackets", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Latest Frame Header Telemetry
        latestPacket?.header?.let { header ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Latest Frame Header",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Frame #: ${header.frameNumber} | Subframe: ${header.subFrameNumber}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Packet Length: ${header.totalPacketLen} bytes", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Detected Objects: ${header.numDetectedObj} | TLV Count: ${header.numTLVs}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = "CPU Cycles: ${header.timeCpuCycles}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.scanAndConnect() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Connect Radar")
            }
            OutlinedButton(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect")
            }
        }

        // Quick Command Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.sendConfig("sensorStop") },
                modifier = Modifier.weight(1f)
            ) {
                Text("sensorStop")
            }
            OutlinedButton(
                onClick = { viewModel.sendConfig("version") },
                modifier = Modifier.weight(1f)
            ) {
                Text("version")
            }
        }

        // Hex Output Box
        Text(
            text = "Incoming UART Data (Hex):",
            style = MaterialTheme.typography.labelMedium
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(8.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = rawHexData,
                color = Color.Green,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
