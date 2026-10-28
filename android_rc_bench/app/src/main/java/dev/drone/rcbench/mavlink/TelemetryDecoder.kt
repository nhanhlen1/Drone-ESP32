package dev.drone.rcbench.mavlink

/** Official common-dialect field units. Sentinel values remain raw and must be
 * rendered as unavailable (e.g. SYS_STATUS 65535mV, -1cA/-1%; RC RSSI255).
 * GPS_RAW_INT eph/epv are HDOP/VDOP times100, NOT accuracy in centimetres.
 */
sealed class Telemetry {
    data class Heartbeat(
        val customMode: Long,
        val vehicleType: Int,
        val autopilot: Int,
        val baseMode: Int,
        val systemStatus: Int,
        val mavlinkVersion: Int,
    ) : Telemetry() {
        val armed: Boolean get() = baseMode and 128 != 0
    }

    data class SysStatus(
        val voltageMv: Int,
        val currentCentiAmp: Int,
        val batteryRemainingPercent: Int,
        val sensorsPresent: Long,
        val sensorsEnabled: Long,
        val sensorsHealthy: Long,
        val sensorsPresentExtended: Long = 0,
        val sensorsEnabledExtended: Long = 0,
        val sensorsHealthyExtended: Long = 0,
    ) : Telemetry()

    data class Attitude(
        val timeBootMs: Long,
        val rollRad: Float,
        val pitchRad: Float,
        val yawRad: Float,
        val rollSpeedRad: Float,
        val pitchSpeedRad: Float,
        val yawSpeedRad: Float,
    ) : Telemetry()

    data class GpsRawInt(
        val timeUsec: Long,
        val latitudeE7: Int,
        val longitudeE7: Int,
        val altitudeMm: Int,
        val eph: Int,
        val epv: Int,
        val velocityCms: Int,
        val courseCentiDegrees: Int,
        val fixType: Int,
        val satellitesVisible: Int,
        val yawCentiDegrees: Int,
    ) : Telemetry()

    data class RcChannels(
        val timeBootMs: Long,
        val channels: IntArray,
        val channelCount: Int,
        val rssi: Int,
    ) : Telemetry()

    data class StatusText(
        val severity: Int,
        val text: String,
        val id: Int,
        val chunkSequence: Int,
    ) : Telemetry()
}

object TelemetryDecoder {
    /** Missing trailing MAVLink2 bytes are zero-filled per protocol, including
     * a partially present multi-byte extension. Decoding does not establish CRC
     * or sender trust: transport must pass only parser-verified frames.
     */
    fun decode(frame: MavFrame): Telemetry? {
        if (frame.incompatFlags != 0) return null
        val definition = CommonDialect.messages[frame.messageId] ?: return null
        if (frame.payload.size > definition.maximumLength) return null
        val p = ByteArray(definition.maximumLength)
        frame.payload.copyInto(p)
        return when (frame.messageId) {
            0 -> Telemetry.Heartbeat(p.u32(0), p.u8(4), p.u8(5), p.u8(6), p.u8(7), p.u8(8))
            1 -> Telemetry.SysStatus(
                p.u16(14), p.i16(16), p[30].toInt(), p.u32(0), p.u32(4), p.u32(8),
                p.u32(31), p.u32(35), p.u32(39),
            )
            24 -> Telemetry.GpsRawInt(
                p.i64(0), p.i32(8), p.i32(12), p.i32(16), p.u16(20), p.u16(22),
                p.u16(24), p.u16(26), p.u8(28), p.u8(29), p.u16(50),
            )
            30 -> {
                val values = FloatArray(6) { p.f32(4 + 4 * it) }
                if (values.any { !it.isFinite() }) null else Telemetry.Attitude(
                    p.u32(0), values[0], values[1], values[2], values[3], values[4], values[5],
                )
            }
            65 -> Telemetry.RcChannels(p.u32(0), IntArray(18) { p.u16(4 + 2 * it) }, p.u8(40), p.u8(41))
            253 -> {
                var end = 1
                while (end < 51 && p[end] != 0.toByte()) end++
                Telemetry.StatusText(p.u8(0), p.copyOfRange(1, end).toString(Charsets.UTF_8), p.u16(51), p.u8(53))
            }
            else -> null
        }
    }
}
