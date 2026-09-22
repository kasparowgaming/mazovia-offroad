package pl.mazovia.offroad.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConfidenceLevel { HIGH, MEDIUM, LOW, UNKNOWN }

@Serializable
enum class RoadDataSource { ROUTING_GRAPH, OSM, BDOT_GUGIK, RIDE_TRACE, IMPORTED_DATA, UNKNOWN }

@Serializable
enum class EvidenceMethod {
    EXPLICIT_TAG, ENCODED_SURFACE_VALUE, SECONDARY_ATTRIBUTE, DERIVED_FROM_ROAD_CLASS,
    SOURCE_REPRESENTATION, OBSERVED_TRACE, MULTI_SOURCE_CORROBORATION, UNKNOWN
}

/** UNKNOWN is the sole source only when no known source exists. */
internal fun validSources(sources: Set<RoadDataSource>): Boolean =
    sources.isNotEmpty() && (sources == setOf(RoadDataSource.UNKNOWN) || RoadDataSource.UNKNOWN !in sources)

@Serializable
data class ExistenceEvidence(
    val confidence: ConfidenceLevel,
    val sources: Set<RoadDataSource>,
    val method: EvidenceMethod
) {
    init { require(validSources(sources)) }

    companion object {
        val UNKNOWN = ExistenceEvidence(ConfidenceLevel.UNKNOWN, setOf(RoadDataSource.UNKNOWN), EvidenceMethod.UNKNOWN)
    }
}

@Serializable
data class SurfaceEvidence(
    val classification: Surface,
    val confidence: ConfidenceLevel,
    val sources: Set<RoadDataSource>,
    val method: EvidenceMethod
) {
    init { require(validSources(sources)) }

    companion object {
        val UNKNOWN = SurfaceEvidence(Surface.UNKNOWN, ConfidenceLevel.UNKNOWN, setOf(RoadDataSource.UNKNOWN), EvidenceMethod.UNKNOWN)
    }
}

@Serializable
data class RoadDataConfidence(
    val existenceEvidence: ExistenceEvidence,
    val surfaceEvidence: SurfaceEvidence
) {
    companion object {
        val UNKNOWN = RoadDataConfidence(ExistenceEvidence.UNKNOWN, SurfaceEvidence.UNKNOWN)
    }
}

/** The only production interpretation of raw road metadata. Secondary sources are supplied only when real data exists. */
object RoadDataConfidenceResolver {
    fun resolve(
        surface: Surface = Surface.UNKNOWN,
        trackType: TrackType = TrackType.UNKNOWN,
        highway: HighwayType = HighwayType.UNKNOWN,
        existenceSources: Set<RoadDataSource> = setOf(RoadDataSource.UNKNOWN),
        surfaceSource: RoadDataSource = RoadDataSource.OSM
    ): RoadDataConfidence {
        val known = existenceSources - RoadDataSource.UNKNOWN
        val existence = when {
            known.size >= 2 -> ExistenceEvidence(ConfidenceLevel.HIGH, known, EvidenceMethod.MULTI_SOURCE_CORROBORATION)
            known.isEmpty() -> ExistenceEvidence.UNKNOWN
            RoadDataSource.RIDE_TRACE in known && known.size == 1 ->
                ExistenceEvidence(ConfidenceLevel.MEDIUM, known, EvidenceMethod.OBSERVED_TRACE)
            else -> ExistenceEvidence(ConfidenceLevel.MEDIUM, known, EvidenceMethod.SOURCE_REPRESENTATION)
        }
        val surfaceEvidence = when {
            surface != Surface.UNKNOWN -> SurfaceEvidence(surface, ConfidenceLevel.HIGH, setOf(surfaceSource),
                if (surfaceSource == RoadDataSource.ROUTING_GRAPH) EvidenceMethod.ENCODED_SURFACE_VALUE
                else EvidenceMethod.EXPLICIT_TAG)
            // grade1 describes a solid track bed, not necessarily asphalt or
            // another paved material. Do not assert a physical surface.
            trackType == TrackType.GRADE1 -> SurfaceEvidence(Surface.UNKNOWN, ConfidenceLevel.UNKNOWN,
                setOf(surfaceSource), EvidenceMethod.SECONDARY_ATTRIBUTE)
            trackType != TrackType.UNKNOWN -> SurfaceEvidence(
                Surface.UNPAVED,
                ConfidenceLevel.MEDIUM, setOf(surfaceSource), EvidenceMethod.SECONDARY_ATTRIBUTE
            )
            highway.isOffRoadCandidate -> SurfaceEvidence(Surface.UNKNOWN, ConfidenceLevel.UNKNOWN,
                setOf(surfaceSource), EvidenceMethod.DERIVED_FROM_ROAD_CLASS)
            else -> SurfaceEvidence.UNKNOWN
        }
        return RoadDataConfidence(existence, surfaceEvidence)
    }
}
