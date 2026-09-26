package com.ceecept.music.audio.spatial

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spatial positioning system — guidelines §3.
 *
 * Coordinate convention (§3.1):
 *  - Azimuth   -180°(left) .. +180°(right), 0° = centre front
 *  - Elevation  -90°(below) .. +90°(above), 0° = ear level
 *  - Distance  metres from the listener
 *
 * Cartesian: X = left(-)/right(+), Y = below(-)/above(+), Z = behind(-)/front(+).
 */
object Geometry {

    const val SPEED_OF_SOUND = 343f

    /** Head width used by the ITD model (§6.2 / Appendix A.1). */
    const val HEAD_WIDTH_M = 0.2f

    /** Sphere radius used by the Woodworth ITD refinement. */
    const val HEAD_RADIUS_M = 0.0875f

    fun rad(deg: Float): Float = (deg * Math.PI / 180.0).toFloat()

    fun deg(rad: Float): Float = (rad * 180.0 / Math.PI).toFloat()

    /** Wrap an angle into -180..+180. */
    fun wrapDeg(deg: Float): Float {
        var a = deg % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    /** §3.1 spherical → cartesian. Result is written into [out] (x, y, z). */
    fun sphericalToCartesian(azimuthDeg: Float, elevationDeg: Float, distanceM: Float, out: FloatArray) {
        val az = rad(azimuthDeg)
        val el = rad(elevationDeg)
        out[0] = distanceM * cos(el) * sin(az)
        out[1] = distanceM * sin(el)
        out[2] = distanceM * cos(el) * cos(az)
    }

    /** §3.1 cartesian → spherical. Result is written into [out] (azimuth, elevation, distance). */
    fun cartesianToSpherical(x: Float, y: Float, z: Float, out: FloatArray) {
        val distance = sqrt(x * x + y * y + z * z)
        out[0] = deg(atan2(x, z))
        out[1] = deg(asin((y / (distance + 1e-10f)).coerceIn(-1f, 1f)))
        out[2] = distance
    }

    /**
     * §4.1 angular distance between two directions, via the spherical law of cosines.
     * Returns degrees in 0..180.
     */
    fun angularDistanceDeg(az1: Float, el1: Float, az2: Float, el2: Float): Float {
        val a1 = rad(az1)
        val e1 = rad(el1)
        val a2 = rad(az2)
        val e2 = rad(el2)
        val cosD = (sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a2 - a1)).coerceIn(-1f, 1f)
        return deg(acos(cosD))
    }
}

/**
 * §3.2 listener-centric reference frame. Every spatial calculation in the engine is
 * expressed relative to this pose, so head-tracking only has to move one object.
 */
class ListenerPose {
    var x = 0f
    var y = 0f
    var z = 0f

    /** Head rotation in degrees. */
    var yaw = 0f
    var pitch = 0f
    var roll = 0f

    val isIdentity: Boolean
        get() = x == 0f && y == 0f && z == 0f && yaw == 0f && pitch == 0f && roll == 0f

    /**
     * Object position (spherical, absolute) → position relative to the listener (spherical).
     * [out] receives azimuth, elevation, distance.
     */
    fun relativeSpherical(
        azimuthDeg: Float,
        elevationDeg: Float,
        distanceM: Float,
        out: FloatArray,
        scratch: FloatArray
    ) {
        if (isIdentity) {
            out[0] = Geometry.wrapDeg(azimuthDeg)
            out[1] = elevationDeg
            out[2] = distanceM
            return
        }
        Geometry.sphericalToCartesian(azimuthDeg, elevationDeg, distanceM, scratch)
        var rx = scratch[0] - x
        var ry = scratch[1] - y
        var rz = scratch[2] - z
        if (yaw != 0f || pitch != 0f || roll != 0f) {
            val yawRad = Geometry.rad(-yaw)
            val pitchRad = Geometry.rad(-pitch)
            val rollRad = Geometry.rad(-roll)
            // Yaw (around Y).
            val xYaw = rx * cos(yawRad) - rz * sin(yawRad)
            val zYaw = rx * sin(yawRad) + rz * cos(yawRad)
            // Pitch (around X).
            val yPitch = ry * cos(pitchRad) - zYaw * sin(pitchRad)
            val zPitch = ry * sin(pitchRad) + zYaw * cos(pitchRad)
            // Roll (around Z).
            val xRoll = xYaw * cos(rollRad) - yPitch * sin(rollRad)
            val yRoll = xYaw * sin(rollRad) + yPitch * cos(rollRad)
            rx = xRoll
            ry = yRoll
            rz = zPitch
        }
        Geometry.cartesianToSpherical(rx, ry, rz, out)
    }

    fun reset() {
        x = 0f; y = 0f; z = 0f; yaw = 0f; pitch = 0f; roll = 0f
    }
}
