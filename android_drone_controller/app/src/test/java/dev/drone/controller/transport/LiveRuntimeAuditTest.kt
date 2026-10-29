package dev.drone.controller.transport

import dev.drone.controller.command.ArmCommandStatus
import dev.drone.controller.command.ArmRequestResult
import dev.drone.controller.control.BenchEngine
import dev.drone.controller.control.MonotonicClock
import dev.drone.controller.mavlink.*
import org.junit.Assert.*
import org.junit.Test

/** Review tests of the APK runtime boundary with decoded, parser-verified frame
 * inputs. They do not replace the separate real TCP/SITL/instrumentation tests. */
class LiveRuntimeAuditTest {
    private class Clock(var now:Long=10_000L):MonotonicClock { override fun nowMs()=now }
    private class Rig {
        val clock=Clock()
        val engine=BenchEngine(clock)
        val runtime=LiveControlRuntime(clock)
        private var sequence=0
        fun frame(id:Int,payload:ByteArray) {
            clock.now++
            val f=MavFrame(sequence++ and 255,42,1,id,payload)
            engine.onFrame(f);runtime.observe(f,engine.snapshot())
        }
        fun heartbeat(armed:Boolean=false,custom:Int=0) {
            frame(0,ByteArray(9).apply{putI32(0,custom);this[4]=2;this[5]=3;this[6]=(if(armed)129 else 1).toByte();this[7]=(if(armed)4 else 3).toByte();this[8]=3})
        }
        fun parameter(name:String,value:Float) {
            frame(22,ByteArray(25).apply{putF32(0,value);putU16(4,100);putU16(6,0);name.toByteArray(Charsets.US_ASCII).copyInto(this,8);this[24]=9})
        }
        fun landed(){frame(245,byteArrayOf(0,1))}
        fun ack(command:Int,result:Int=0) {frame(77,ByteArray(10).apply{putU16(0,command);this[2]=result.toByte();this[8]=255.toByte();this[9]=190.toByte()})}
        fun health() {
            frame(1,ByteArray(43).apply{putI32(0,1 shl 28);putI32(4,1 shl 28);putI32(8,1 shl 28);putU16(14,12_000);putU16(16,65535);this[30]=(-1).toByte()})
            frame(193,ByteArray(26).apply{putU16(20,1)})
            landed()
        }
        fun echo(channels:List<Int> = listOf(1500,1500,1100,1500)){frame(65,ByteArray(42).apply{channels.forEachIndexed{i,v->putU16(4+i*2,v)};this[40]=4;this[41]=255.toByte()})}
        fun ready(rudderArming:Int?=0,flightModeChannel:Int?=6,rollOption:Int?=0) {
            engine.connected();engine.foreground(true)
            runtime.connecting(7);runtime.connected();runtime.foreground(true);runtime.select(ControlProfile.CONTROL)
            heartbeat()
            parameter("MAV_GCS_SYSID",255f);parameter("MAV_GCS_SYSID_HI",0f)
            parameter("RC_OPTIONS",33f);parameter("RC_OVERRIDE_TIME",.5f)
            rudderArming?.let{parameter("ARMING_RUDDER",it.toFloat())}
            flightModeChannel?.let{parameter("FLTMODE_CH",it.toFloat())}
            listOf("RCMAP_ROLL","RCMAP_PITCH","RCMAP_THROTTLE","RCMAP_YAW").forEachIndexed{i,name->parameter(name,(i+1).toFloat())}
            for(n in 1..4) {
                parameter("RC${n}_MIN",1100f);parameter("RC${n}_MAX",1900f)
                parameter("RC${n}_TRIM",1500f);parameter("RC${n}_REVERSED",0f)
                (if(n==1)rollOption else 0)?.let{parameter("RC${n}_OPTION",it.toFloat())}
            }
            health()
        }
        fun enabled() {
            ready();assertTrue(runtime.enable(true,false,true))
            runtime.input(0f,0f,0f,0f);runtime.pulse()
            val permit=runtime.plan();assertNotNull(runtime.snapshot().authority.reason,permit)
            runtime.written(permit!!);echo()
            assertTrue(runtime.snapshot().authority.canTransmit)
            assertTrue(runtime.snapshot().authority.rcEchoVerified)
        }
    }

    @Test fun invalidChangesToRequiredParametersRevokeBeforeTheNextWrite() {
        for((name,value) in listOf("RCMAP_ROLL" to 1.5f,"RC_OPTIONS" to 33.5f,"RC1_REVERSED" to 1f,
            "RC1_MIN" to 1500f,"MAV_GCS_SYSID" to Float.MAX_VALUE,"ARMING_RUDDER" to 1f,
            "FLTMODE_CH" to 1f,"RC1_OPTION" to 41f)) {
            val r=Rig();r.enabled();r.parameter(name,value)
            r.runtime.input(0f,0f,0f,0f);r.runtime.pulse()
            assertFalse("Changed invalid $name must revoke old readback",r.runtime.snapshot().authority.canTransmit)
            assertNull(r.runtime.plan())
        }
    }

    @Test fun missingOrEnabledStickArmingReadbackCannotEnableFourChannelProfiles() {
        for(value in listOf(null,1,2))for(profile in listOf(ControlProfile.CONTROL,ControlProfile.BENCH_4CH)) {
            val r=Rig();r.ready(value);r.runtime.select(profile)
            assertFalse("ARMING_RUDDER=$value profile=$profile",r.runtime.enable(true,true,true))
            r.runtime.input(0f,0f,0f,1f);r.runtime.pulse()
            assertFalse(r.runtime.snapshot().authority.canTransmit)
            assertNull(r.runtime.plan())
        }
    }

    @Test fun missingOrConflictingModeAndAuxiliaryChannelReadbackCannotEnableFourChannels() {
        val cases=listOf<Pair<Int?,Int?>>(null to 0,1 to 0,6 to null,6 to 41)
        for((modeChannel,option) in cases)for(profile in listOf(ControlProfile.CONTROL,ControlProfile.BENCH_4CH)) {
            val r=Rig();r.ready(flightModeChannel=modeChannel,rollOption=option);r.runtime.select(profile)
            assertFalse("FLTMODE_CH=$modeChannel RC1_OPTION=$option",r.runtime.enable(true,true,true))
            assertFalse(r.runtime.snapshot().authority.canTransmit)
            assertNull(r.runtime.plan())
        }
    }

    @Test fun armRequiresAllThreeCenteredAxesEvenWhenTheirNonCenteredRcEchoMatches() {
        for(axis in listOf(0,1,3)) {
            val r=Rig();r.enabled();r.clock.now+=60L
            val input=FloatArray(4);input[axis]=.2f
            r.runtime.input(input[0],input[1],input[2],input[3]);r.runtime.pulse()
            val permit=r.runtime.plan();assertNotNull(permit)
            r.runtime.written(permit!!);r.echo(r.runtime.snapshot().lastSent!!)
            assertTrue(r.runtime.snapshot().authority.rcEchoVerified)
            val result=r.runtime.requestArm(true,true)
            assertTrue(result is ArmRequestResult.Rejected)
            assertTrue((result as ArmRequestResult.Rejected).reason.contains("giữa"))
        }
    }

    @Test fun changingEvenAValidCalibrationRevokesInsteadOfAutomaticallyAdaptingAnActiveSession() {
        val r=Rig();r.enabled();r.parameter("RC1_MAX",1800f)
        r.runtime.input(0f,0f,0f,0f);r.runtime.pulse()
        assertFalse(r.runtime.snapshot().authority.canTransmit)
        assertNull(r.runtime.plan())
    }

    @Test fun explicitReadbackRefreshInvalidatesCachedParametersImmediately() {
        val r=Rig();r.enabled();r.runtime.stop("Đọc lại")
        r.runtime.refreshReadback()
        assertFalse(r.runtime.enable(true,false,true))
        r.parameter("RC1_MIN",1100f) // A single fresh item cannot qualify the old batch.
        assertFalse(r.runtime.enable(true,false,true))
        assertFalse(r.runtime.snapshot().authority.canTransmit)
    }

    @Test fun staleSnapshotCannotMakeModeOrDisarmLookFreshWhenTcpStopsDeliveringBytes() {
        val r=Rig();r.ready();r.heartbeat(true);r.landed()
        r.clock.now+=1500L
        assertFalse(r.runtime.requestMode(0,true))
        assertTrue(r.runtime.requestArm(false,true) is ArmRequestResult.Rejected)
        assertNull(r.runtime.queued())
    }

    @Test fun parserFaultBlocksCommandsUntilANewVerifiedAutopilotHeartbeat() {
        val r=Rig();r.ready();r.runtime.fault("CRC/partial timeout")
        assertFalse(r.runtime.requestMode(0,true))
        assertTrue(r.runtime.requestArm(false,true) is ArmRequestResult.Rejected)
        r.heartbeat()
        assertTrue(r.runtime.requestMode(0,true))
    }

    @Test fun modeAckBeforeSocketWriteCannotConfirmAnUnsentCommand() {
        val r=Rig();r.ready();assertTrue(r.runtime.requestMode(0,true))
        r.ack(176);r.heartbeat(custom=0)
        assertNull(r.runtime.snapshot().modeCommand.ackResult)
        assertFalse(r.runtime.snapshot().modeCommand.heartbeatConfirmed)
        val command=r.runtime.queued();assertNotNull(command)
        r.runtime.commandWritten(command!!);r.ack(176);r.heartbeat(custom=0)
        assertTrue(r.runtime.snapshot().modeCommand.socketWritten)
        assertTrue(r.runtime.snapshot().modeCommand.heartbeatConfirmed)
    }

    @Test fun armAckBeforeSocketWriteCannotConfirmAnUnsentIntent() {
        val r=Rig();r.enabled();assertTrue(r.runtime.requestArm(true,true) is ArmRequestResult.Accepted)
        r.ack(400);r.heartbeat(true)
        assertEquals(ArmCommandStatus.INTENT_ACCEPTED,r.runtime.snapshot().armCommand.status)
        assertFalse(r.runtime.snapshot().armCommand.socketWritten)
        assertFalse(r.runtime.snapshot().armCommand.heartbeatConfirmed)
    }

    @Test fun lossOfFocusCancelsQueuedCommandsAndDoesNotRestoreThemOnReturn() {
        val r=Rig();r.ready();assertTrue(r.runtime.requestMode(0,true))
        r.runtime.foreground(false)
        assertNull(r.runtime.queued());assertFalse(r.runtime.requestMode(0,true))
        assertTrue(r.runtime.requestArm(false,true) is ArmRequestResult.Rejected)
        r.runtime.foreground(true)
        assertNull(r.runtime.queued())
        assertFalse(r.runtime.snapshot().modeCommand.heartbeatConfirmed)
    }
}
