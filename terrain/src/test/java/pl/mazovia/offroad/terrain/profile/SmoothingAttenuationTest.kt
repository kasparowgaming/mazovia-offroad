package pl.mazovia.offroad.terrain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.mazovia.offroad.terrain.elevation.SyntheticProfile
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.SPACING
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.pipeline
import pl.mazovia.offroad.terrain.profile.ProfileFixtures.raw
import kotlin.math.abs

/**
 * DESIGN §12.3 attenuation table (TA-000B-C1): theory, TARGET budgets and required event outcomes are the
 * committed values; tests run each ramp at two grid alignments (ramp start on a sample and half-way between).
 */
class SmoothingAttenuationTest {

    private data class Case(
        val name: String,
        val grade: Double,
        val lengthM: Double,
        val theoryPeakPct: Double,
        val budgetPp: Double,
        val type: GradeEventType
    )

    private val cases = listOf(
        Case("+12% / 40 m", 0.12, 40.0, 11.52, 1.0, GradeEventType.CLIMB),
        Case("+15% / 25 m", 0.15, 25.0, 11.25, 4.5, GradeEventType.CLIMB),
        Case("+8% / 100 m", 0.08, 100.0, 8.00, 0.5, GradeEventType.CLIMB),
        Case("-12% / 40 m", -0.12, 40.0, -11.52, 1.0, GradeEventType.DESCENT),
        Case("+10% / 30 m short hill then flat", 0.10, 30.0, 8.40, 2.0, GradeEventType.CLIMB)
    )

    private fun index(sM: Double) = Math.round(sM / SPACING).toInt()

    @Test
    fun `ramp attenuation matches theory and committed budgets`() {
        println("ATTENUATION | case | align m | raw peak % | filtered peak % | atten pp | atten % | raw dH m | filtered dH m | shift m | event")
        for (c in cases) for (align in listOf(0.0, 2.5)) {
            val x0 = 400.0 + align
            val p = pipeline(raw(SyntheticProfile.Builder(100.0).ramp(x0, c.lengthM, c.grade).build(), 1000.0))

            var rawPeak = 0.0
            for (i in 0 until p.raw.size - 1) {
                val g = (p.raw.heightAt(i + 1)!! - p.raw.heightAt(i)!!) / SPACING
                if (abs(g) > abs(rawPeak)) rawPeak = g
            }
            var filteredPeak = 0.0
            for (i in 0 until p.grade.size) p.grade.gradeAt(i)?.let { if (abs(it) > abs(filteredPeak)) filteredPeak = it }
            val attenuationPp = (abs(rawPeak) - abs(filteredPeak)) * 100.0
            val relativePct = attenuationPp / (abs(rawPeak) * 100.0) * 100.0

            val rawChange = c.grade * c.lengthM
            val filteredChange = p.filtered.heightAt(index(x0 + c.lengthM + 30.0))!! - p.filtered.heightAt(index(x0 - 30.0))!!

            val event = p.events.firstOrNull { it.type == c.type }
            val shift = event?.let { maxOf(abs(it.startDistanceM - x0), abs(it.endDistanceM - (x0 + c.lengthM))) }
            println(
                "ATTENUATION | ${c.name} | $align | ${"%.2f".format(rawPeak * 100)} | ${"%.2f".format(filteredPeak * 100)} | " +
                    "${"%.2f".format(attenuationPp)} | ${"%.1f".format(relativePct)} | ${"%.3f".format(rawChange)} | " +
                    "${"%.3f".format(filteredChange)} | ${shift?.let { "%.1f".format(it) }} | " +
                    "${event?.let { "${it.type}/${it.eventClass} len=${it.lengthM} dH=${"%.2f".format(it.elevationChangeM)}" }}"
            )

            assertEquals("${c.name} raw peak", c.grade, rawPeak, 1e-9)
            assertTrue("${c.name}@$align theory gap", abs(filteredPeak * 100 - c.theoryPeakPct) <= 0.3 + 1e-9)
            assertTrue("${c.name}@$align attenuation $attenuationPp pp > ${c.budgetPp}", attenuationPp <= c.budgetPp + 1e-9)
            assertEquals("${c.name}@$align plateau elevation change", rawChange, filteredChange, 0.1)
            assertNotNull("${c.name}@$align event lost", event)
            assertTrue("${c.name}@$align boundary shift $shift", shift!! <= 15.0)
            assertEquals(1, p.events.size)
        }
    }

    @Test
    fun `isolated false spike is removed by the filter, kept in raw and flagged`() {
        val p = pipeline(raw(SyntheticProfile.Builder(100.0).spike(600.0, 1.0, 3.0).build(), 1000.0))
        assertEquals(103.0, p.raw.heightAt(120)!!, 1e-9)
        var maxAbs = 0.0
        for (i in 0 until p.grade.size) p.grade.gradeAt(i)?.let { maxAbs = maxOf(maxAbs, abs(it)) }
        println("ATTENUATION | isolated spike +3 m | filtered max |g| = ${"%.3f".format(maxAbs * 100)} % | events=${p.events.size}")
        assertTrue(maxAbs <= 0.01)
        assertTrue(p.events.isEmpty())
        assertTrue(p.anomalies.any { it.reason == AnomalyReason.ISOLATED_SPIKE && it.startIndex == 120 })
    }

    @Test
    fun `step discontinuity is flagged and reported`() {
        val p = pipeline(raw(SyntheticProfile.Builder(100.0).step(402.5, 2.0).build(), 1000.0))
        var maxAbs = 0.0
        for (i in 0 until p.grade.size) p.grade.gradeAt(i)?.let { maxAbs = maxOf(maxAbs, abs(it)) }
        println("ATTENUATION | step 2 m | filtered max |g| = ${"%.2f".format(maxAbs * 100)} % | events=${p.events.map { "${it.type}/${it.eventClass}" }} (report only)")
        assertTrue(p.anomalies.any { it.reason == AnomalyReason.STEP })
        assertEquals(102.0, p.raw.heightAt(index(410.0))!!, 1e-9)
    }
}
