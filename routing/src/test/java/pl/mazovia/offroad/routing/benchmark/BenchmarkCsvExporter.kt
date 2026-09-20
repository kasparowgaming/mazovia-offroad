package pl.mazovia.offroad.routing.benchmark

import java.io.File
import java.util.Locale

internal object BenchmarkCsvExporter {
    fun writeSummaryCsv(file: File, results: List<BenchmarkSummaryResult>) {
        file.bufferedWriter().use { writer ->
            writer.write("scenarioName,origin,destination,profile,runIndex,isWarmup,success,failureReason,baselineDistanceMeters,selectedDistanceMeters,detourPercent,terrainDistanceMeters,terrainPercent,longestContinuousTerrainMeters,terrainRunCount,longestAsphaltConnectorMeters,selectedTournamentScore,candidateCountAttempted,candidateCountSuccessful,candidateCountRejectedByDetour,candidateCountFailed,baselineTimeMs,tournamentTimeMs,totalTimeMs,uTurnCount,shortManeuverLegCount,usedHeapBeforeBytes,usedHeapAfterBytes,heapDeltaBytes,selectedCandidateId,tournamentStatus,executionSemantics,tournamentTimeNanos\n")
            for (r in results) writer.write(row(listOf(
                r.scenarioName, "${r.origin.latitude}_${r.origin.longitude}", "${r.destination.latitude}_${r.destination.longitude}",
                r.profile.name, r.runIndex, r.isWarmup, r.success, r.failureReason,
                r.baselineDistanceMeters, r.selectedDistanceMeters, r.detourPercent, r.terrainDistanceMeters,
                r.terrainPercent, r.longestContinuousTerrainMeters, r.terrainRunCount, r.longestAsphaltConnectorMeters,
                r.selectedTournamentScore, r.candidateCountAttempted, r.candidateCountSuccessful, r.candidateCountRejectedByDetour,
                r.candidateCountFailed, r.baselineTimeMs, r.tournamentTimeMs, r.totalTimeMs, r.uTurnCount,
                r.shortManeuverLegCount, r.usedHeapBeforeBytes, r.usedHeapAfterBytes, r.heapDeltaBytes,
                r.selectedCandidateId, r.tournamentStatus, r.executionSemantics, r.tournamentTimeNanos
            )) + "\n")
        }
    }

    fun writeCandidateCsv(file: File, candidates: List<BenchmarkCandidateResult>) {
        file.bufferedWriter().use { writer ->
            writer.write("scenarioName,profile,runIndex,isWarmup,candidateId,isBaseline,distanceMeters,detourPercent,terrainPercent,longestContinuousTerrainMeters,terrainRunCount,longestAsphaltConnectorMeters,tournamentScore,acceptedByDetourGuard,routingSuccess,elapsedMs,failureReason,selected,source,status,rejectionReason,baselineDistanceMeters,detourRatio,extraDistanceMeters,detourLimitPercent,maxAllowedDistanceMeters,exceedsDetourLimit,terrainDistanceMeters,asphaltDistanceMeters,routeId,waypoints\n")
            for (c in candidates) writer.write(row(listOf(
                c.scenarioName, c.profile.name, c.runIndex, c.isWarmup, c.candidateId, c.isBaseline,
                c.distanceMeters, c.detourPercent, c.terrainPercent, c.longestContinuousTerrainMeters, c.terrainRunCount,
                c.longestAsphaltConnectorMeters, c.tournamentScore, c.acceptedByDetourGuard, c.routingSuccess,
                c.elapsedMs, c.failureReason, c.selected, c.source, c.status, c.rejectionReason,
                c.baselineDistanceMeters, c.detourRatio, c.extraDistanceMeters, c.detourLimitPercent,
                c.maxAllowedDistanceMeters, c.exceedsDetourLimit, c.terrainDistanceMeters,
                c.asphaltDistanceMeters, c.routeId, c.waypoints
            )) + "\n")
        }
    }

    // Additional precision preserves measurable sub-millisecond tournaments and exact ratios.
    // RFC-style quoting preserves errors/waypoints containing commas, quotes or newlines.
    internal fun row(values: List<Any?>): String = values.joinToString(",") { value ->
        val text = when (value) {
            null -> ""
            is Double -> if (value.isFinite()) String.format(Locale.US, "%.6f", value) else ""
            else -> value.toString()
        }
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"${text.replace("\"", "\"\"")}\"" else text
    }
}
