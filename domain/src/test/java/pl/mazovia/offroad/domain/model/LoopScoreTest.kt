package pl.mazovia.offroad.domain.model

import org.junit.Assert.*
import org.junit.Test

class LoopScoreTest {

    @Test
    fun `high off-road loop scores higher than low off-road`() {
        val highOffRoad = createMetrics(offRoad = 0.8, asphalt = 0.2, distance = 50_000.0)
        val lowOffRoad = createMetrics(offRoad = 0.3, asphalt = 0.7, distance = 50_000.0)
        
        val scoreHigh = LoopScore.calculate(highOffRoad, 50)
        val scoreLow = LoopScore.calculate(lowOffRoad, 50)
        
        assertTrue("High off-road should score higher", scoreHigh.overallScore > scoreLow.overallScore)
    }

    @Test
    fun `loop matching target distance scores higher`() {
        val onTarget = createMetrics(distance = 50_000.0)
        val offTarget = createMetrics(distance = 80_000.0)
        
        val scoreOn = LoopScore.calculate(onTarget, 50)
        val scoreOff = LoopScore.calculate(offTarget, 50)
        
        assertTrue("On-target distance should have lower error", 
            scoreOn.targetDistanceError < scoreOff.targetDistanceError)
    }

    @Test
    fun `profile differences produce distinct scoring`() {
        val metrics = createMetrics(offRoad = 0.6, distance = 50_000.0)
        val score = LoopScore.calculate(metrics, 50)
        
        assertTrue("Score should be between 0 and 1", score.overallScore in 0.0..1.0)
        assertTrue("Off-road share should match", score.offRoadShare in 0.59..0.61)
    }

    private fun createMetrics(
        offRoad: Double = 0.5,
        asphalt: Double = 1.0 - offRoad,
        distance: Double = 50_000.0
    ): RouteMetrics {
        val offRoadDist = distance * offRoad
        val asphaltDist = distance * asphalt
        return RouteMetrics(
            totalDistanceMeters = distance,
            estimatedTimeSeconds = (distance / 10).toLong(),
            offRoadDistanceMeters = offRoadDist,
            asphaltDistanceMeters = asphaltDist,
            longestAsphaltConnectorMeters = asphaltDist * 0.5,
            surfaceDistribution = mapOf(Surface.DIRT to offRoad, Surface.ASPHALT to asphalt),
            dataConfidenceScore = 0.7,
            continuitScore = 0.8,
            explorationScore = 0.5
        )
    }
}
