package pl.mazovia.offroad.ui.readiness

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.domain.readiness.ComponentReadiness
import pl.mazovia.offroad.domain.readiness.RidePackReadiness
import pl.mazovia.offroad.domain.model.RouteSource

@Composable
fun RidePackReadinessCard(
    readiness: RidePackReadiness?,
    onPrepareClicked: () -> Unit
) {
    if (readiness == null) return

    val overallColor = when (readiness.overallStatus) {
        ComponentReadiness.READY -> Color(0xFF388E3C)
        ComponentReadiness.PARTIAL -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (readiness.overallStatus) {
                ComponentReadiness.READY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ComponentReadiness.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Przygotowanie do jazdy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Resources & Capabilities
            ComponentStatusRow(
                label = "Mapa",
                status = readiness.resource.mapResource,
                readyMessage = "Gotowa",
                notReadyMessage = "Brak danych offline",
                partialMessage = "Częściowa",
                unknownMessage = "Nie można potwierdzić pokrycia"
            )

            ComponentStatusRow(
                label = if (readiness.route?.source == RouteSource.IMPORTED_GPX) "Ślad GPX" else "Trasa",
                status = readiness.resource.routeArtifact,
                readyMessage = "Gotowa",
                notReadyMessage = "Brak danych",
                partialMessage = "Niekompletna"
            )

            ComponentStatusRow(
                label = "Prowadzenie offline",
                status = readiness.capability.followGeometry,
                readyMessage = "Gotowe",
                notReadyMessage = "Niedostępne"
            )

            if (readiness.route?.source != RouteSource.IMPORTED_GPX) {
                ComponentStatusRow(
                    label = "Przeliczanie trasy",
                    status = readiness.capability.fullRerouting,
                    readyMessage = "Gotowe",
                    notReadyMessage = "Niedostępne offline",
                    notRequiredMessage = "Niewymagane"
                )
            }

            ComponentStatusRow(
                label = "Powrót do trasy",
                status = readiness.capability.recoveryGuidance,
                readyMessage = "Gotowy",
                notReadyMessage = "Niedostępny",
                partialMessage = "Ograniczony"
            )

            ComponentStatusRow(
                label = "Dane nawierzchni",
                status = readiness.resource.persistedData,
                readyMessage = "Gotowe",
                notReadyMessage = "Brak danych",
                notRequiredMessage = "Brak danych"
            )

            // Device Status checks
            if (readiness.device.locationPermission == ComponentReadiness.NOT_READY ||
                readiness.device.gpsAvailable == ComponentReadiness.NOT_READY) {
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                if (readiness.device.locationPermission == ComponentReadiness.NOT_READY) {
                    ComponentStatusRow("Lokalizacja GPS", ComponentReadiness.NOT_READY, "", "Brak uprawnień", "")
                }
                if (readiness.device.gpsAvailable == ComponentReadiness.NOT_READY) {
                    ComponentStatusRow("Sygnał GPS", ComponentReadiness.NOT_READY, "", "Wyłączony", "")
                }
            }

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // Overall Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when (readiness.overallStatus) {
                        ComponentReadiness.READY -> Icons.Default.CheckCircle
                        ComponentReadiness.PARTIAL -> Icons.Default.Warning
                        else -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = overallColor
                )
                
                Column {
                    Text(
                        text = when (readiness.overallStatus) {
                            ComponentReadiness.READY -> "GOTOWE DO JAZDY OFFLINE"
                            ComponentReadiness.PARTIAL -> "JAZDA OFFLINE MOŻLIWA"
                            else -> "NIEGOTOWE DO JAZDY OFFLINE"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = overallColor
                    )
                    if (readiness.overallStatus == ComponentReadiness.PARTIAL) {
                        Text(
                            text = "Niektóre funkcje będą ograniczone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Provide a button if there's a problem we might be able to fix via OfflineDataScreen
            if (readiness.resource.offlineGraph == ComponentReadiness.NOT_READY || 
                readiness.resource.mapResource == ComponentReadiness.NOT_READY) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onPrepareClicked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Dane offline: Uzupełnij")
                }
            }
        }
    }
}

@Composable
private fun ComponentStatusRow(
    label: String,
    status: ComponentReadiness,
    readyMessage: String,
    notReadyMessage: String,
    partialMessage: String = "Ograniczone",
    notRequiredMessage: String = "Nie dotyczy",
    unknownMessage: String = "Nie można potwierdzić"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = when (status) {
                    ComponentReadiness.READY -> Icons.Default.CheckCircle
                    ComponentReadiness.PARTIAL -> Icons.Default.Warning
                    ComponentReadiness.NOT_READY -> Icons.Default.Error
                    ComponentReadiness.NOT_REQUIRED -> Icons.Default.CheckCircle
                    ComponentReadiness.UNKNOWN -> Icons.Default.HelpOutline
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = when (status) {
                    ComponentReadiness.READY -> Color(0xFF388E3C)
                    ComponentReadiness.NOT_REQUIRED -> Color.Gray
                    ComponentReadiness.PARTIAL -> Color(0xFFF57C00)
                    ComponentReadiness.NOT_READY -> MaterialTheme.colorScheme.error
                    ComponentReadiness.UNKNOWN -> Color.Gray
                }
            )
            Text(
                text = when (status) {
                    ComponentReadiness.READY -> readyMessage
                    ComponentReadiness.PARTIAL -> partialMessage
                    ComponentReadiness.NOT_READY -> notReadyMessage
                    ComponentReadiness.NOT_REQUIRED -> notRequiredMessage
                    ComponentReadiness.UNKNOWN -> unknownMessage
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (status) {
                    ComponentReadiness.READY -> Color(0xFF388E3C)
                    ComponentReadiness.NOT_REQUIRED -> Color.Gray
                    ComponentReadiness.PARTIAL -> Color(0xFFF57C00)
                    ComponentReadiness.NOT_READY -> MaterialTheme.colorScheme.error
                    ComponentReadiness.UNKNOWN -> Color.Gray
                }
            )
        }
    }
}
