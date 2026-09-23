package pl.mazovia.offroad.domain.roughness

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class VerticalAccelerationProjectorTest {

    private val projector = RotationVectorProjector()

    @Test
    fun testIdentityOrientation() {
        // Flat on table. Gravity points down (-Z in world, but Android's SensorManager.GRAVITY_EARTH is a magnitude 9.8).
        // Let's assume acceleration vector is [0, 0, 9.8 + 2.0] (gravity + upward movement)
        // Orientation is identity quaternion [0, 0, 0, 1]
        val v = projector.projectToVertical(0f, 0f, 11.80665f, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(2.0f, v, 0.01f)
    }

    @Test
    fun test90DegreeRotation() {
        // Device rotated 90 degrees around X axis (standing up).
        // Y is up, Z is horizontal.
        // If we push it "up" (world Z), it registers on device Y.
        // Quaternion for 90 deg around X: q1 = sin(45) = 0.707, q0 = cos(45) = 0.707
        val q1 = sqrt(0.5f)
        val q0 = sqrt(0.5f)
        val v = projector.projectToVertical(0f, 11.80665f, 0f, floatArrayOf(q1, 0f, 0f, q0))
        assertEquals(2.0f, v, 0.01f)
    }

    @Test
    fun testSimultaneousHorizontalAcceleration() {
        // Flat on table, moving up and sideways.
        // Accel: X=5.0 (horizontal), Y=0, Z=11.80665 (vertical + gravity)
        val v = projector.projectToVertical(5f, 0f, 11.80665f, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(2.0f, v, 0.01f)
    }

    @Test
    fun testArbitraryOrientation() {
        // 45 degrees around Y axis
        val q2 = sqrt(0.5f) // wait, sin(22.5 deg) = 0.38268, cos(22.5 deg) = 0.92388
        val qy = 0.3826834f
        val qw = 0.9238795f
        
        // World vertical acceleration 2.0 + 9.80665 = 11.80665
        // Device X = 11.80665 * -sin(45) = -8.348
        // Device Z = 11.80665 * cos(45) = 8.348
        val v = projector.projectToVertical(-8.3485f, 0f, 8.3485f, floatArrayOf(0f, qy, 0f, qw))
        assertEquals(2.0f, v, 0.01f)
    }
}
