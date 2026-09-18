package pl.mazovia.offroad.ui.rides

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.data.repository.FeedbackRepository
import pl.mazovia.offroad.data.repository.RideRepository
import pl.mazovia.offroad.designsystem.components.EmptyView
import pl.mazovia.offroad.designsystem.theme.MazoviaColors
import pl.mazovia.offroad.domain.model.Ride

@Composable
fun RidesScreen(
    rideRepository: RideRepository,
    feedbackRepository: FeedbackRepository
) {
    val rides by rideRepository.getAllRides().collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Jazdy",
                style = MaterialTheme.typography.headlineLarge
            )
        }

        if (rides.isEmpty()) {
            item {
                EmptyView(
                    message = "Brak zarejestrowanych jazd.\nRozpocznij nagrywanie podczas jazdy.",
                    icon = Icons.Default.DirectionsBike
                )
            }
        } else {
            items(rides.size) { index ->
                val ride = rides[index]
                RideCard(ride = ride)
            }
        }
    }
}

@Composable
private fun RideCard(ride: Ride) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${String.format("%.1f", ride.distanceMeters / 1000)} km",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            ride.metrics?.let { metrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${metrics.offRoadPercentage.toInt()}% terenu",
                        color = MazoviaColors.ForestGreen,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${ride.durationSeconds / 60} min",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (ride.pendingFeedback.isNotEmpty()) {
                Text(
                    text = "${ride.pendingFeedback.size} pytań o drogę",
                    style = MaterialTheme.typography.labelSmall,
                    color = MazoviaColors.Warning
                )
            }
        }
    }
}
