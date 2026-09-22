package pl.mazovia.offroad.ui.confidence

import pl.mazovia.offroad.domain.model.ConfidenceLevel
import pl.mazovia.offroad.domain.model.RoadDataConfidence
import pl.mazovia.offroad.domain.model.Route
import pl.mazovia.offroad.domain.model.Surface
import kotlin.math.floor

enum class SurfaceDataCategory(val label: String) {
    DEFINED("Określona"),
    ESTIMATED("Oszacowana"),
    UNKNOWN("Brak danych")
}

enum class SurfaceDataAvailability { AVAILABLE, ALL_UNKNOWN, NO_DISTANCE }

data class SurfaceCoverageRow(
    val category: SurfaceDataCategory,
    val distanceMeters: Double,
    val percent: Int
)

data class RoadConfidenceUiState(
    val availability: SurfaceDataAvailability,
    val rows: List<SurfaceCoverageRow>,
    val roadDataNote: String?
)

/** The sole mapping of TASK-008 evidence into rider-facing surface categories. */
object RoadConfidenceUiMapper {
    fun category(confidence: RoadDataConfidence): SurfaceDataCategory {
        val evidence = confidence.surfaceEvidence
        if (evidence.classification == Surface.UNKNOWN) return SurfaceDataCategory.UNKNOWN
        return when (evidence.confidence) {
            ConfidenceLevel.HIGH -> SurfaceDataCategory.DEFINED
            ConfidenceLevel.MEDIUM, ConfidenceLevel.LOW -> SurfaceDataCategory.ESTIMATED
            ConfidenceLevel.UNKNOWN -> SurfaceDataCategory.UNKNOWN
        }
    }

    fun map(route: Route): RoadConfidenceUiState {
        val summary = route.roadDataConfidenceSummary
        val total = summary.totalDistanceMeters
        if (total <= 0.0) return RoadConfidenceUiState(SurfaceDataAvailability.NO_DISTANCE, emptyList(), null)

        val categories = SurfaceDataCategory.entries
        val distances = DoubleArray(categories.size)
        route.segments.forEach { segment ->
            distances[category(segment.roadDataConfidence).ordinal] += segment.distanceMeters
        }
        val percentages = roundedPercentages(distances, total)
        val rows = categories.mapIndexed { index, category ->
            SurfaceCoverageRow(category, distances[index], percentages[index])
        }
        val weakRoadDistance = summary.lowExistenceDistanceMeters + summary.unknownExistenceDistanceMeters
        val roadNote = if (weakRoadDistance > 0.0)
            "Dane o przebiegu drogi są ograniczone na części trasy."
        else null
        return RoadConfidenceUiState(
            availability = if (distances[SurfaceDataCategory.UNKNOWN.ordinal] == total)
                SurfaceDataAvailability.ALL_UNKNOWN else SurfaceDataAvailability.AVAILABLE,
            rows = rows,
            roadDataNote = roadNote
        )
    }

    /** Largest remainder: stable ties follow category order and visible values sum to 100. */
    private fun roundedPercentages(distances: DoubleArray, total: Double): IntArray {
        val raw = distances.map { it / total * 100.0 }
        val rounded = IntArray(distances.size) { floor(raw[it]).toInt() }
        val remaining = (100 - rounded.sum()).coerceIn(0, distances.size)
        val order = raw.indices.sortedWith(compareByDescending<Int> { raw[it] - floor(raw[it]) }.thenBy { it })
        repeat(remaining) { rounded[order[it]]++ }
        return rounded
    }
}
