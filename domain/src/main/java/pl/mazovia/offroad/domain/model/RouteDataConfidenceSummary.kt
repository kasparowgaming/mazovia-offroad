package pl.mazovia.offroad.domain.model

/**
 * Exact segment distances are the sole aggregation basis. Each distance enters
 * exactly one surface bucket and exactly one existence bucket.
 */
data class RouteDataConfidenceSummary(
    val totalDistanceMeters: Double,
    val explicitSurfaceDistanceMeters: Double,
    val inferredSurfaceDistanceMeters: Double,
    val unknownSurfaceDistanceMeters: Double,
    val highExistenceDistanceMeters: Double,
    val mediumExistenceDistanceMeters: Double,
    val lowExistenceDistanceMeters: Double,
    val unknownExistenceDistanceMeters: Double
) {
    fun percentage(distanceMeters: Double): Double =
        if (totalDistanceMeters > 0.0) distanceMeters / totalDistanceMeters * 100.0 else 0.0

    companion object {
        fun fromSegments(segments: List<RouteSegment>): RouteDataConfidenceSummary {
            require(segments.all { it.distanceMeters.isFinite() && it.distanceMeters >= 0.0 })
            val total = segments.sumOf { it.distanceMeters }
            fun distanceWhere(predicate: (RouteSegment) -> Boolean) =
                segments.filter(predicate).sumOf { it.distanceMeters }
            return RouteDataConfidenceSummary(
                totalDistanceMeters = total,
                explicitSurfaceDistanceMeters = distanceWhere {
                    val evidence = it.roadDataConfidence.surfaceEvidence
                    evidence.classification != Surface.UNKNOWN &&
                        (evidence.method == EvidenceMethod.EXPLICIT_TAG ||
                            evidence.method == EvidenceMethod.ENCODED_SURFACE_VALUE)
                },
                inferredSurfaceDistanceMeters = distanceWhere {
                    val evidence = it.roadDataConfidence.surfaceEvidence
                    evidence.classification != Surface.UNKNOWN &&
                        evidence.method != EvidenceMethod.EXPLICIT_TAG &&
                        evidence.method != EvidenceMethod.ENCODED_SURFACE_VALUE
                },
                unknownSurfaceDistanceMeters = distanceWhere {
                    it.roadDataConfidence.surfaceEvidence.classification == Surface.UNKNOWN
                },
                highExistenceDistanceMeters = distanceWhere {
                    it.roadDataConfidence.existenceEvidence.confidence == ConfidenceLevel.HIGH
                },
                mediumExistenceDistanceMeters = distanceWhere {
                    it.roadDataConfidence.existenceEvidence.confidence == ConfidenceLevel.MEDIUM
                },
                lowExistenceDistanceMeters = distanceWhere {
                    it.roadDataConfidence.existenceEvidence.confidence == ConfidenceLevel.LOW
                },
                unknownExistenceDistanceMeters = distanceWhere {
                    it.roadDataConfidence.existenceEvidence.confidence == ConfidenceLevel.UNKNOWN
                }
            )
        }
    }
}
