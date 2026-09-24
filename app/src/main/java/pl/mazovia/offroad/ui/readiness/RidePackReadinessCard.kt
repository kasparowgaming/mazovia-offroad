package pl.mazovia.offroad.ui.readiness

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.domain.model.RouteSource
import pl.mazovia.offroad.domain.readiness.ComponentReadiness
import pl.mazovia.offroad.domain.readiness.RidePackReadiness

@Composable
fun RidePackReadinessCard(
    readiness: RidePackReadiness?,
    onPrepareClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (readiness == null) return

    // Presentation state only: expanded or collapsed
    // Tied to the route ID so it resets when route changes
    var isExpanded by rememberSaveable(readiness.route?.id) { mutableStateOf(false) }

    val icon = when (readiness.overallStatus) {
        ComponentReadiness.READY -> Icons.Default.CheckCircle
        ComponentReadiness.PARTIAL -> Icons.Default.Warning
        else -> Icons.Default.Error
    }
    
    val iconColor = when (readiness.overallStatus) {
        ComponentReadiness.READY -> Color(0xFF388E3C)
        ComponentReadiness.PARTIAL -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.error
    }
    
    val contentColor = when (readiness.overallStatus) {
        ComponentReadiness.READY -> MaterialTheme.colorScheme.onSurface
        ComponentReadiness.PARTIAL -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    
    val statusText = when (readiness.overallStatus) {
        ComponentReadiness.READY -> "Gotowe do jazdy"
        ComponentReadiness.PARTIAL -> "Jazda offline możliwa"
        else -> "Brak danych do wyjazdu"
    }
    
    val subText = when (readiness.overallStatus) {
        ComponentReadiness.PARTIAL -> "Niektóre funkcje będą ograniczone."
        else -> null
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { isExpanded = !isExpanded },
        colors = CardDefaults.cardColors(
            containerColor = when (readiness.overallStatus) {
                ComponentReadiness.READY -> MaterialTheme.colorScheme.surfaceVariant
                ComponentReadiness.PARTIAL -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compact Default Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    if (subText != null) {
                        Text(
                            text = subText,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Zwiń" else "Rozwiń",
                    tint = contentColor
                )
            }

            // Expanded State Details
            if (isExpanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                
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
                    unknownMessage = "Pokrycie niepotwierdzone"
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
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (readiness.device.locationPermission == ComponentReadiness.NOT_READY) {
                        ComponentStatusRow("Lokalizacja GPS", ComponentReadiness.NOT_READY, "", "Brak uprawnień", "")
                    }
                    if (readiness.device.gpsAvailable == ComponentReadiness.NOT_READY) {
                        ComponentStatusRow("Sygnał GPS", ComponentReadiness.NOT_READY, "", "Wyłączony", "")
                    }
                }

                // Provide a button if there's a problem we might be able to fix via OfflineDataScreen
                if (readiness.resource.offlineGraph == ComponentReadiness.NOT_READY || 
                    readiness.resource.mapResource == ComponentReadiness.NOT_READY) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            // Stop expansion propagation to prevent it from immediately collapsing when clicking
                            onPrepareClicked()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Dane offline: Uzupełnij")
                    }
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
    unknownMessage: String = "Pokrycie niepotwierdzone"
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
                    ComponentReadiness.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
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
