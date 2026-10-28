package dev.drone.controller.mavlink

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

    data class GlobalPosition(
        val timeBootMs:Long,val latitudeE7:Int,val longitudeE7:Int,val altitudeMm:Int,val relativeAltitudeMm:Int,
        val velocityNorthCms:Int,val velocityEastCms:Int,val velocityDownCms:Int,val headingCentiDegrees:Int,
    ):Telemetry()
    /** Absolute pressure is measured; barometric altitude requires an established reference. */
    data class ScaledPressure(val timeBootMs:Long,val absoluteHpa:Float,val differentialHpa:Float,val temperatureCentiDegrees:Int):Telemetry()
    data class ServoOutput(val timeUsec:Long,val port:Int,val channels:IntArray):Telemetry()
    data class ParameterValue(val name:String,val value:Float,val type:Int,val count:Int,val index:Int):Telemetry()
    /** An ACK concerns a COMMAND_LONG request, never proves RC override acceptance. */
    data class CommandAck(val command:Int,val result:Int,val progress:Int,val resultParam2:Int,val targetSystem:Int,val targetComponent:Int):Telemetry()
    data class BatteryStatus(val id:Int,val voltagesMv:IntArray,val currentCentiAmp:Int,val consumedMah:Int,
        val remainingPercent:Int,val temperatureCentiDegrees:Int,val timeRemainingSeconds:Int):Telemetry()
    data class AutopilotVersion(val flightSoftwareVersion:Long,val boardVersion:Long,val capabilities:Long,
        val uid:Long,val flightCustomVersion:ByteArray):Telemetry()
    data class EkfStatus(val flags:Int,val velocityVariance:Float,val horizontalVariance:Float,
        val verticalVariance:Float,val compassVariance:Float,val terrainVariance:Float):Telemetry()

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
        if (frame.incompatFlags != 0 || frame.payload.isEmpty()) return null
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
            22 -> {
                val value=p.f32(0)
                if(!value.isFinite()) null else {
                    val end=(8 until 24).firstOrNull { p[it]==0.toByte() } ?:24
                    Telemetry.ParameterValue(p.copyOfRange(8,end).toString(Charsets.US_ASCII),value,p.u8(24),p.u16(4),p.u16(6))
                }
            }
            29 -> {
                val pressure=p.f32(4);val diff=p.f32(8)
                if(!pressure.isFinite() || !diff.isFinite()) null else Telemetry.ScaledPressure(p.u32(0),pressure,diff,p.i16(12))
            }
            33 -> Telemetry.GlobalPosition(p.u32(0),p.i32(4),p.i32(8),p.i32(12),p.i32(16),p.i16(20),p.i16(22),p.i16(24),p.u16(26))
            36 -> Telemetry.ServoOutput(p.u32(0),p.u8(20),IntArray(16){if(it<8)p.u16(4+it*2) else p.u16(21+(it-8)*2)})
            77 -> Telemetry.CommandAck(p.u16(0),p.u8(2),p.u8(3),p.i32(4),p.u8(8),p.u8(9))
            147 -> Telemetry.BatteryStatus(p.u8(32),IntArray(14){if(it<10)p.u16(10+it*2) else p.u16(41+(it-10)*2)},p.i16(30),p.i32(0),p[35].toInt(),p.i16(8),p.i32(36))
            148 -> Telemetry.AutopilotVersion(p.u32(16),p.u32(28),p.i64(0),p.i64(8),p.copyOfRange(36,44))
            193 -> {
                val values=FloatArray(5){p.f32(it*4)}
                if(values.any{!it.isFinite()}) null else Telemetry.EkfStatus(p.u16(20),values[0],values[1],values[2],values[3],values[4])
            }
            253 -> {
                var end = 1
                while (end < 51 && p[end] != 0.toByte()) end++
                Telemetry.StatusText(p.u8(0), p.copyOfRange(1, end).toString(Charsets.UTF_8), p.u16(51), p.u8(53))
            }
            else -> null
        }
    }
}
