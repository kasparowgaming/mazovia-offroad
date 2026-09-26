package pl.mazovia.offroad.ui.riding.terrain

import org.junit.Assert.*
import org.junit.Test
import pl.mazovia.offroad.debug.NavigationStateReplayer as Replay
import pl.mazovia.offroad.domain.model.NavigationStatus
import pl.mazovia.offroad.terrain.projection.ProjectionMode

class TerrainPresenterTest {
    private fun tick(p: TerrainPresenter, ms: Long) = p.frame(ms * 1_000_000)
    private fun emit(p: TerrainPresenter, ms: Long, s: Double, speed: Double = 10.0) =
        p.onNavigationState(Replay.state(Replay.route(), s, speed), ms * 1_000_000)

    @Test fun forwardSmoothAndSettles() {
        val p = TerrainPresenter()
        emit(p, 0, 100.0)
        val at0 = tick(p, 0).distanceAlongM!!
        val at33 = tick(p, 33).distanceAlongM!!
        val at990 = tick(p, 990).distanceAlongM!!
        assertTrue(at33 > at0 && at33 - at0 <= 10.0 * .033 * 1.3 + 1e-6)
        assertTrue(at990 > at33)
        emit(p, 1000, 110.0)
        var last = 1000L
        while (last < 2500) { last += 33; tick(p, last) }
        val settled = tick(p, 3000)
        assertFalse(settled.needsAnimation)
        assertEquals(settled.distanceAlongM!! - 50, settled.windowStartM!!, 1e-6)
        assertEquals(settled.distanceAlongM!! + 600, settled.windowEndM!!, 1e-6)
    }

    @Test fun jitterHasNoCumulativeBackwardRegression() {
        val samples = Replay.replay(Replay.jitter(), untilMillis = 6500)
        val values = samples.mapNotNull { it.display.distanceAlongM }
        values.zipWithNext().forEach { (before, after) ->
            assertTrue("single backward step ${before - after}", before - after <= 1.00001)
        }
        Replay.jitter().forEachIndexed { i, emission ->
            val end = Replay.jitter().getOrNull(i + 1)?.atMillis ?: 6501L
            val interval = samples.filter { it.atMillis in emission.atMillis until end }
                .mapNotNull { it.display.distanceAlongM }
            val backward = interval.zipWithNext().sumOf { (a, b) -> (a - b).coerceAtLeast(0.0) }
            assertTrue("interval backward motion $backward", backward <= 1.00001)
        }
    }

    @Test fun confirmedReversalMovesBackwards() {
        val samples = Replay.replay(Replay.reversal(), untilMillis = 7900)
        fun emitted(ms: Long) = samples.first { it.atMillis == ms }.display
        assertEquals(ProjectionMode.HOLD, emitted(2000).target!!.projection.mode)
        assertEquals(ProjectionMode.ATTACHED, emitted(3000).target!!.projection.mode)
        assertTrue(emitted(1000).target!!.projection.distanceAlongM!! -
            emitted(3000).target!!.projection.distanceAlongM!! > 8.0)
        assertTrue(emitted(3000).target!!.projection.distanceAlongM!! >
            emitted(4000).target!!.projection.distanceAlongM!!)
        val reverse = samples.filter { it.atMillis in 3000L..4900L }
            .mapNotNull { it.display.distanceAlongM }
        assertTrue(reverse.last() < reverse.first() - 1.0)
        reverse.zipWithNext().forEach { (a, b) -> assertTrue("reverse display $a to $b", b <= a + 1e-5) }

        assertEquals(ProjectionMode.HOLD, emitted(5000).target!!.projection.mode)
        assertEquals(ProjectionMode.ATTACHED, emitted(6000).target!!.projection.mode)
        assertTrue(emitted(6000).target!!.projection.distanceAlongM!! -
            emitted(4000).target!!.projection.distanceAlongM!! > 8.0)
        val forward = samples.filter { it.atMillis in 6000L..7900L }
            .mapNotNull { it.display.distanceAlongM }
        assertTrue(forward.last() > forward.first() + 1.0)
        forward.zipWithNext().forEach { (a, b) -> assertTrue("forward display $a to $b", b + 1e-5 >= a) }
    }

    @Test fun reversalLagIsInterpolationNotPrediction() {
        // §15.2: the +15 m cap bounds the predicted target; after a confirmed reversal the display may lag further
        // while it converges at ≤ 1 m per frame, without a snap.
        val samples = Replay.replay(Replay.reversal(), untilMillis = 4900)
        fun emitted(ms: Long) = samples.first { it.atMillis == ms }.display
        val reversedTarget = emitted(3000).target!!.projection.distanceAlongM!!
        assertTrue("display lags the reversed target", emitted(3000).distanceAlongM!! - reversedTarget > 15.0)
        val window = samples.filter { it.atMillis in 3000L..4900L }.mapNotNull { it.display.distanceAlongM }
        window.zipWithNext().forEach { (a, b) -> assertTrue("backward step ${a - b}", a - b in -1e-5..1.00001) }
        val latestTarget = emitted(4000).target!!.projection.distanceAlongM!!
        assertTrue("prediction never beyond target − 15 m", window.min() >= latestTarget - 15.0 - 1e-5)
        // In the reversed direction the lagging display is behind its target, so it closes at the v·dt·1.3 clamp.
        val lagAt4000 = emitted(4000).distanceAlongM!! - latestTarget
        val lagAt4900 = window.last() - latestTarget
        assertTrue("lag $lagAt4000 -> $lagAt4900 keeps closing", lagAt4900 < lagAt4000 - 1.0)
    }

    @Test fun reattachmentBehindConvergesWithoutReversalOrSnap() {
        val script = Replay.reattach()
        val samples = Replay.replay(script, untilMillis = 8900)
        fun emitted(ms: Long) = samples.first { it.atMillis == ms }.display
        assertEquals(TerrainVisualMode.OFF_ROUTE, emitted(3000).mode)
        val reattached = emitted(4000)
        assertEquals(ProjectionMode.ATTACHED, reattached.target!!.projection.mode)
        assertEquals(130.0, reattached.target!!.projection.distanceAlongM!!, 0.5)
        samples.forEach { assertEquals("road", it.display.target!!.projection.routeId) }

        val values = samples.mapNotNull { it.display.distanceAlongM }
        values.zipWithNext().forEach { (a, b) -> assertTrue("backward step ${a - b}", a - b <= 1.00001) }
        val beforeReattach = samples.last { it.atMillis < 4000 }.display.distanceAlongM!!
        assertTrue("no snap to the re-attached target", beforeReattach - reattached.distanceAlongM!! <= 1.00001)
        assertTrue(beforeReattach - 130.0 > 50.0)

        val firstInterval = samples.filter { it.atMillis in 4000L until 5000L }.mapNotNull { it.display.distanceAlongM }
        val backward = firstInterval.zipWithNext().sumOf { (a, b) -> (a - b).coerceAtLeast(0.0) }
        assertTrue("convergence continues across frames: $backward", backward > 20.0)
        // Not a reversal: prediction still runs forward from the re-attached target, never behind it.
        assertTrue("display ${firstInterval.min()} behind the re-attached target", firstInterval.min() >= 130.0 - 1e-6)

        val forward = samples.filter { it.atMillis in 7000L..8900L }.mapNotNull { it.display.distanceAlongM }
        forward.zipWithNext().forEach { (a, b) -> assertTrue("forward display $a to $b", b + 1e-5 >= a) }
        assertTrue(forward.last() > forward.first() + 1.0)
        assertTrue(forward.last() in 170.0..185.00001)
    }

    @Test fun predictionStopsAtOneSecondAndFifteenMetres() {
        val p = TerrainPresenter()
        emit(p, 0, 100.0, 30.0)
        for (i in 1..40) tick(p, i * 33L)
        val at1320 = tick(p, 1320).distanceAlongM!!
        assertTrue(at1320 <= 115.00001)
        assertEquals(at1320, tick(p, 2500).distanceAlongM!!, 1e-6)
    }

    @Test fun predictionCapsDistanceTimeAndRouteEnd() {
        val fast = TerrainPresenter()
        emit(fast, 0, 100.0, 30.0)
        assertTrue(tick(fast, 1000).distanceAlongM!! <= 115.00001)
        val atOneSecond = tick(fast, 1000).distanceAlongM!!
        assertEquals(atOneSecond, tick(fast, 3000).distanceAlongM!!, 1e-6)

        val slow = TerrainPresenter()
        emit(slow, 0, 100.0, 5.0)
        assertTrue(tick(slow, 1000).distanceAlongM!! <= 105.00001)
        assertEquals(tick(slow, 1000).distanceAlongM!!, tick(slow, 3000).distanceAlongM!!, 1e-6)

        val end = TerrainPresenter()
        emit(end, 0, 995.0, 30.0)
        assertTrue(tick(end, 3000).distanceAlongM!! <= Replay.route().totalDistanceMeters + 1e-5)
    }

    /** Frames every 33 ms of virtual time in (fromMs, toMs]. */
    private fun frames(p: TerrainPresenter, fromMs: Long, toMs: Long): List<TerrainDisplayState> =
        generateSequence(fromMs + 33) { it + 33 }.takeWhile { it <= toMs }.map { tick(p, it) }.toList()

    @Test fun separatedAcceptedCorrectionsStayBoundedAndReplenish() {
        val p = TerrainPresenter()
        // 10 m/s reported, but updates 1, 4 and 7 accept only +5 m: each is an ordinary correction behind the display.
        val targets = listOf(100.0, 105.0, 115.0, 125.0, 130.0, 140.0, 150.0, 155.0)
        var previous: Double? = null
        val backwardPerUpdate = targets.mapIndexed { i, s ->
            val start = i * 1000L
            val emitted = emit(p, start, s)
            assertEquals(ProjectionMode.ATTACHED, emitted.target!!.projection.mode)
            val values = listOfNotNull(previous) + emitted.distanceAlongM!! +
                frames(p, start, start + 999).map { it.distanceAlongM!! }
            values.zipWithNext().forEach { (a, b) -> assertTrue("backward step ${a - b}", a - b <= 1.00001) }
            previous = values.last()
            values.zipWithNext().sumOf { (a, b) -> (a - b).coerceAtLeast(0.0) }
        }
        backwardPerUpdate.forEach { assertTrue("backward per accepted update $it", it <= 1.00001) }
        assertEquals("allowance is per accepted update, not per ride", listOf(1, 4, 7),
            backwardPerUpdate.indices.filter { backwardPerUpdate[it] > 0.99 })
        assertEquals("not pinned ahead of the accepted target", 165.0, tick(p, 9000).distanceAlongM!!, 0.05)
    }

    @Test fun repeatedHoldRefreshesFreshnessWithoutRestartingPredictionOrWobbling() {
        val p = TerrainPresenter()
        emit(p, 0, 100.0, 30.0)
        frames(p, 0, 999)
        val peak = tick(p, 1000).distanceAlongM!!
        assertEquals("prediction capped at +15 m", 115.0, peak, 0.05)
        val held = mutableListOf<Double>()
        for (i in 1..8) {
            val start = i * 1000L
            val emitted = emit(p, start, 96.0, 30.0) // 4 m behind the accepted 100 m: projection HOLD
            assertEquals(ProjectionMode.HOLD, emitted.target!!.projection.mode)
            assertEquals(100.0, emitted.target!!.projection.distanceAlongM!!, 0.5)
            assertFalse("HOLD refreshes freshness", emitted.positionStale)
            held += emitted.distanceAlongM!!
            frames(p, start, start + 999).forEach {
                held += it.distanceAlongM!!
                assertFalse("HOLD refreshes freshness", it.positionStale)
                assertFalse("HOLD does not restart prediction", it.needsAnimation)
            }
        }
        assertEquals("no sawtooth and no extended prediction", 0.0, held.maxOrNull()!! - held.minOrNull()!!, 1e-9)
        assertEquals(peak, held.first(), 1e-9)
        // Freshness comes from the last HOLD emission (8000 ms), not from the accepted target (0 ms).
        assertFalse(tick(p, 10_999).positionStale)
        assertTrue(tick(p, 11_000).positionStale)
        assertFalse(tick(p, 12_999).showStaleIndicator)
        assertTrue(tick(p, 13_000).showStaleIndicator)
        assertEquals(peak, tick(p, 13_000).distanceAlongM!!, 1e-9)

        val slow = TerrainPresenter()
        emit(slow, 0, 100.0, 5.0)
        frames(slow, 0, 999)
        val afterOneSecond = tick(slow, 1000).distanceAlongM!!
        assertEquals("prediction stops after 1.0 s", 105.0, afterOneSecond, 0.05)
        for (i in 1..4) {
            val start = i * 1000L
            assertEquals(ProjectionMode.HOLD, emit(slow, start, 97.0, 5.0).target!!.projection.mode)
            frames(slow, start, start + 999).forEach { assertEquals(afterOneSecond, it.distanceAlongM!!, 1e-9) }
        }
    }

    @Test fun stopAndStationarySilenceNeverStale() {
        val p = TerrainPresenter()
        emit(p, 0, 100.0, 0.0)
        val later = tick(p, 10_000)
        assertEquals(100.0, later.distanceAlongM!!, 1.0)
        assertFalse(later.positionStale)
        assertFalse(later.showStaleIndicator)
        assertFalse(later.needsAnimation)
    }

    @Test fun stoppingAnActiveRideKeepsItsDisplayedPosition() {
        val p = TerrainPresenter()
        emit(p, 0, 100.0)
        tick(p, 500)
        val before = tick(p, 1000).distanceAlongM!!
        emit(p, 1000, 101.0, 0.0)
        assertEquals(before, tick(p, 10_000).distanceAlongM!!, 1e-6)
    }

    @Test fun movingSilenceFreezesAndTransitionsAtThreeAndFiveSeconds() {
        val p = TerrainPresenter()
        emit(p, 0, 100.0)
        tick(p, 1000)
        val frozen = tick(p, 2999)
        assertFalse(frozen.positionStale)
        assertEquals(frozen.distanceAlongM!!, tick(p, 3000).distanceAlongM!!, 1e-6)
        assertTrue(tick(p, 3000).positionStale)
        assertFalse(tick(p, 4999).showStaleIndicator)
        assertTrue(tick(p, 5000).showStaleIndicator)
    }

    @Test fun routeReplacementDetachedAndEnd() {
        val changed = Replay.replay(Replay.routeChange(), untilMillis = 1100)
        val replaced = changed.first { it.atMillis == 1000L }.display
        assertEquals("new", replaced.target!!.projection.routeId)
        assertTrue(replaced.distanceAlongM!! < 100.0)
        val detached = Replay.replay(Replay.offRoute(), untilMillis = 3000)
        val off = detached.last().display
        assertEquals(TerrainVisualMode.OFF_ROUTE, off.mode)
        assertEquals(80.0, off.distanceToRouteM!!, 1e-6)
        assertEquals(detached.first { it.atMillis == 1000L }.display.distanceAlongM!!, off.distanceAlongM!!, 1e-6)
        val end = Replay.replay(Replay.arrived(), untilMillis = 3000).last().display
        assertEquals(TerrainVisualMode.ARRIVED, end.mode)
        assertTrue(end.distanceAlongM!! <= Replay.route().totalDistanceMeters + 1e-5)
    }

    /** Replays [prefix] with frames up to [arriveMs], then emits ARRIVED at 1000 m with or without a position. */
    private fun arriveAfter(prefix: List<Replay.Emission>, arriveMs: Long, position: Boolean,
                            onBeforeArrival: (TerrainDisplayState) -> Unit = {}): Pair<TerrainPresenter, TerrainDisplayState> {
        val p = TerrainPresenter()
        var now = 0L
        prefix.forEach { emission ->
            while (now + 33 < emission.atMillis) { now += 33; tick(p, now) }
            now = emission.atMillis
            p.onNavigationState(emission.state, now * 1_000_000)
        }
        while (now + 33 < arriveMs) { now += 33; tick(p, now) }
        onBeforeArrival(tick(p, arriveMs - 1))
        val arrived = Replay.state(Replay.route(), 1000.0, 10.0, NavigationStatus.ARRIVED)
            .let { if (position) it else it.copy(currentPosition = null) }
        return p to p.onNavigationState(arrived, arriveMs * 1_000_000)
    }

    @Test fun arrivedWithoutPositionIsTerminalAtRouteEnd() {
        val reattaching = Replay.reattach().take(5) // 4000 ms: re-attached at 130 m, display still converging back
        val reversed = Replay.reversal().take(5)    // 4000 ms: confirmed reversal, direction against the route
        listOf(reattaching to 4500L, reversed to 4500L).forEach { (prefix, arriveMs) ->
            val (withPosition, reference) = arriveAfter(prefix, arriveMs, position = true)
            val (p, arrived) = arriveAfter(prefix, arriveMs, position = false) { before ->
                assertTrue("non-terminal before arrival", before.distanceAlongM!! < 900.0)
                assertTrue(before.needsAnimation)
            }
            // The projection re-reports its last accepted s (HOLD) without a position; the presenter imposes the end.
            assertEquals(ProjectionMode.HOLD, arrived.target!!.projection.mode)
            assertEquals(TerrainVisualMode.ARRIVED, arrived.mode)
            val end = reference.distanceAlongM!!
            assertEquals(Replay.route().totalDistanceMeters, end, 0.5)
            assertEquals("display at route end", end, arrived.distanceAlongM!!, 1e-9)
            assertEquals("target at route end", end, arrived.target!!.projection.distanceAlongM!!, 1e-9)
            assertEquals(end - 50.0, arrived.target!!.windowStartM!!, 1e-9)
            assertFalse(arrived.needsAnimation)
            assertNull(arrived.nextTimedUpdateNanos)
            assertFalse(arrived.positionStale)
            // Identical presentation with or without a position.
            listOf(TerrainDisplayState::mode, TerrainDisplayState::distanceAlongM, TerrainDisplayState::windowStartM,
                TerrainDisplayState::windowEndM, TerrainDisplayState::distanceToRouteM,
                TerrainDisplayState::needsAnimation, TerrainDisplayState::nextTimedUpdateNanos)
                .forEach { assertEquals(it.get(reference), it.get(arrived)) }
            assertEquals(reference.target!!.projection.distanceAlongM!!, arrived.target!!.projection.distanceAlongM!!, 1e-9)
            // No prediction, stale reattach/reversal convergence, or overshoot survives the terminal transition.
            for (ms in listOf(arriveMs + 33, arriveMs + 1000, arriveMs + 10_000)) {
                listOf(p, withPosition).forEach { presenter ->
                    val later = tick(presenter, ms)
                    assertEquals(end, later.distanceAlongM!!, 1e-9)
                    assertFalse(later.needsAnimation)
                    assertFalse(later.positionStale)
                }
            }
        }
    }

    @Test fun deterministicReplayAndGpxBreak() {
        val a = Replay.replay(Replay.acceleration(), untilMillis = 3500)
        val b = Replay.replay(Replay.acceleration(), untilMillis = 3500)
        assertEquals(a, b)
        val gpx = Replay.replay(Replay.gpx(), untilMillis = 1000).last().display
        assertEquals("gpx", gpx.target!!.projection.routeId)
        assertTrue(gpx.mode == TerrainVisualMode.ATTACHED || gpx.mode == TerrainVisualMode.HOLD)
    }

    @Test fun scriptedSimulationRespectsMotionAndFreeze() {
        val scripts = listOf(Replay.steady(), Replay.jitter(), Replay.acceleration(),
            Replay.deceleration(), Replay.stopped(), Replay.missing(), Replay.routeChange(),
            Replay.reversal(), Replay.offRoute(), Replay.arrived(), Replay.gpx())
        scripts.forEach { script ->
            val samples = Replay.replay(script, untilMillis = maxOf(6000L, script.last().atMillis + 1000L))
            var previous: TerrainDisplayState? = null
            samples.forEach { sample ->
                val current = sample.display
                val back = (previous?.distanceAlongM ?: current.distanceAlongM ?: 0.0) -
                    (current.distanceAlongM ?: 0.0)
                val replacement = previous?.target?.projection?.routeId != current.target?.projection?.routeId
                val genuineReversal = script === scripts[7] && sample.atMillis in 3000L until 6000L
                if (!replacement && !genuineReversal) assertTrue("backward $back at ${sample.atMillis}", back <= 1.00001)
                previous = current
            }
            script.forEachIndexed { index, emission ->
                val nextEmission = script.getOrNull(index + 1)?.atMillis ?: Long.MAX_VALUE
                val reverseInterval = script === scripts[7] && emission.atMillis in 3000L until 6000L
                val routeReplacement = index > 0 &&
                    script[index - 1].state.route?.id != emission.state.route?.id
                if (!reverseInterval && !routeReplacement) {
                    val interval = samples.filter { it.atMillis >= emission.atMillis && it.atMillis < nextEmission }
                        .mapNotNull { it.display.distanceAlongM }
                    val totalBack = interval.zipWithNext().sumOf { (a, b) -> (a - b).coerceAtLeast(0.0) }
                    assertTrue("interval backward $totalBack at ${emission.atMillis}", totalBack <= 1.00001)
                }
            }
        }
        val loss = Replay.replay(Replay.missing(), untilMillis = 6000)
        assertTrue(loss.last().display.showStaleIndicator)
        val still = Replay.replay(Replay.stopped(), untilMillis = 6000)
        assertFalse(still.last().display.positionStale)
    }
}
