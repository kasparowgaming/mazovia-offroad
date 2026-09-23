package pl.mazovia.offroad.domain.roughness

import kotlin.math.sqrt

interface VerticalAccelerationProjector {
    /**
     * Projects raw accelerometer vector to vertical acceleration.
     * @param accelX Raw X acceleration (m/s2)
     * @param accelY Raw Y acceleration (m/s2)
     * @param accelZ Raw Z acceleration (m/s2)
     * @param orientation Array of orientation vector values (e.g., from rotation vector or gravity)
     * @return vertical acceleration without gravity (m/s2)
     */
    fun projectToVertical(accelX: Float, accelY: Float, accelZ: Float, orientation: FloatArray): Float
}

class RotationVectorProjector : VerticalAccelerationProjector {
    override fun projectToVertical(accelX: Float, accelY: Float, accelZ: Float, orientation: FloatArray): Float {
        // orientation from TYPE_ROTATION_VECTOR: [x*sin(th/2), y*sin(th/2), z*sin(th/2), cos(th/2)]
        val q1 = orientation[0]
        val q2 = orientation[1]
        val q3 = orientation[2]
        val q0 = if (orientation.size >= 4) orientation[3] else {
            val sq = 1.0f - q1*q1 - q2*q2 - q3*q3
            if (sq > 0) sqrt(sq) else 0f
        }

        // We want the Z component of the rotated acceleration vector.
        // Rotation matrix R from quaternion (q0, q1, q2, q3):
        // R_31 = 2 * (q1*q3 - q0*q2)
        // R_32 = 2 * (q2*q3 + q0*q1)
        // R_33 = 1 - 2 * (q1*q1 + q2*q2)
        
        val r31 = 2.0f * (q1 * q3 - q0 * q2)
        val r32 = 2.0f * (q2 * q3 + q0 * q1)
        val r33 = 1.0f - 2.0f * (q1 * q1 + q2 * q2)

        val worldAccelZ = r31 * accelX + r32 * accelY + r33 * accelZ
        
        // STANDARD GRAVITY is 9.80665f
        return worldAccelZ - 9.80665f
    }
}

class GravityFallbackProjector : VerticalAccelerationProjector {
    override fun projectToVertical(accelX: Float, accelY: Float, accelZ: Float, orientation: FloatArray): Float {
        // orientation is TYPE_GRAVITY
        val gx = orientation[0]
        val gy = orientation[1]
        val gz = orientation[2]
        
        // linearAccel = rawAccel - gravity
        val lx = accelX - gx
        val ly = accelY - gy
        val lz = accelZ - gz
        
        // normalize(gravity)
        val gMag = sqrt(gx*gx + gy*gy + gz*gz)
        if (gMag < 0.001f) return 0f
        val ngx = gx / gMag
        val ngy = gy / gMag
        val ngz = gz / gMag
        
        // dot(linearAccel, normalize(gravity))
        return lx * ngx + ly * ngy + lz * ngz
    }
}
