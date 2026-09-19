package pl.mazovia.offroad.routing.benchmark

import java.io.File
import java.util.Locale

internal object BenchmarkCsvExporter {

    fun writeSummaryCsv(file: File, results: List<BenchmarkSummaryResult>) {
        file.bufferedWriter().use { writer ->
            writer.write("scenarioName,origin,destination,profile,runIndex,isWarmup,success,failureReason,baselineDistanceMeters,selectedDistanceMeters,detourPercent,terrainDistanceMeters,terrainPercent,longestContinuousTerrainMeters,terrainRunCount,longestAsphaltConnectorMeters,selectedTournamentScore,candidateCountAttempted,candidateCountSuccessful,candidateCountRejectedByDetour,candidateCountFailed,baselineTimeMs,tournamentTimeMs,totalTimeMs,uTurnCount,shortManeuverLegCount,usedHeapBeforeBytes,usedHeapAfterBytes,heapDeltaBytes\n")
            
            results.forEach { r ->
                val line = buildString {
                    append("${r.scenarioName},")
                    append("${r.origin.latitude}_${r.origin.longitude},")
                    append("${r.destination.latitude}_${r.destination.longitude},")
                    append("${r.profile.name},")
                    append("${r.runIndex},")
                    append("${r.isWarmup},")
                    append("${r.success},")
                    append("${r.failureReason?.replace(",", ";") ?: ""},")
                    
                    append(formatD(r.baselineDistanceMeters)).append(",")
                    append(formatD(r.selectedDistanceMeters)).append(",")
                    append("${r.detourPercent},")
                    
                    append(formatD(r.terrainDistanceMeters)).append(",")
                    append("${r.terrainPercent},")
                    append(formatD(r.longestContinuousTerrainMeters)).append(",")
                    append("${r.terrainRunCount},")
                    append(formatD(r.longestAsphaltConnectorMeters)).append(",")
                    
                    append(formatD(r.selectedTournamentScore)).append(",")
                    
                    append("${r.candidateCountAttempted},")
                    append("${r.candidateCountSuccessful},")
                    append("${r.candidateCountRejectedByDetour},")
                    append("${r.candidateCountFailed},")
                    
                    append("${r.baselineTimeMs},")
                    append("${r.tournamentTimeMs},")
                    append("${r.totalTimeMs},")
                    
                    append("${r.uTurnCount},")
                    append("${r.shortManeuverLegCount},")
                    
                    append("${r.usedHeapBeforeBytes},")
                    append("${r.usedHeapAfterBytes},")
                    append("${r.heapDeltaBytes}")
                }
                writer.write(line + "\n")
            }
        }
    }

    fun writeCandidateCsv(file: File, candidates: List<BenchmarkCandidateResult>) {
        file.bufferedWriter().use { writer ->
            writer.write("scenarioName,profile,runIndex,isWarmup,candidateId,isBaseline,distanceMeters,detourPercent,terrainPercent,longestContinuousTerrainMeters,terrainRunCount,longestAsphaltConnectorMeters,tournamentScore,acceptedByDetourGuard,routingSuccess,elapsedMs,failureReason\n")
            
            candidates.forEach { c ->
                val line = buildString {
                    append("${c.scenarioName},")
                    append("${c.profile.name},")
                    append("${c.runIndex},")
                    append("${c.isWarmup},")
                    
                    append("${c.candidateId},")
                    append("${c.isBaseline},")
                    append(formatD(c.distanceMeters)).append(",")
                    append("${c.detourPercent},")
                    
                    append("${c.terrainPercent},")
                    append(formatD(c.longestContinuousTerrainMeters)).append(",")
                    append("${c.terrainRunCount},")
                    append(formatD(c.longestAsphaltConnectorMeters)).append(",")
                    
                    append(formatD(c.tournamentScore)).append(",")
                    append("${c.acceptedByDetourGuard},")
                    
                    append("${c.routingSuccess},")
                    append("${c.elapsedMs},")
                    append("${c.failureReason?.replace(",", ";") ?: ""}")
                }
                writer.write(line + "\n")
            }
        }
    }

    private fun formatD(value: Double): String = String.format(Locale.US, "%.2f", value)
}
