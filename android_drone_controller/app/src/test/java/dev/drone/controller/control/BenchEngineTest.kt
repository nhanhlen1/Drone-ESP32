package dev.drone.controller.control

import dev.drone.controller.mavlink.MavFrame
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Policy tests use monotonic fake time. Protocol golden vectors are tested separately. */
class BenchEngineTest {
    private class Clock(var now:Long=10_000):MonotonicClock { override fun nowMs()=now }
    private class Rig {
        val clock=Clock(); val engine=BenchEngine(clock); var sequence=0
        fun heartbeat(system:Int=42,component:Int=1,armed:Boolean=false,status:Int=3,vehicle:Int=2,autopilot:Int=3,version:Int=3):MavFrame =
            MavFrame(sequence++ and 255,system,component,0,ByteArray(9).apply {
                this[4]=vehicle.toByte();this[5]=autopilot.toByte();this[6]=(if(armed)128 else 0).toByte()
                this[7]=status.toByte();this[8]=version.toByte()
            })
        fun rc(first:Int=1500,count:Int=0):MavFrame {
            val p=ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
            repeat(18){p.putShort((if(it==0)first else 0).toShort())}
            p.put(count.toByte()).put(255.toByte())
            return MavFrame(sequence++ and 255,42,1,65,p.array())
        }
        fun parameter(name:String,value:Float,system:Int=42):MavFrame {
            val p=ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            p.putFloat(value).putShort(27.toShort()).putShort(0.toShort())
            p.put(name.toByteArray(Charsets.US_ASCII).copyOf(16));p.put(9.toByte())
            return MavFrame(sequence++ and 255,system,1,22,p.array())
        }
        fun sysStatus(unhealthy:Long=0x10010000L,extendedUnhealthy:Long=0):MavFrame {
            val enabled=0x513f002fL
            val p=ByteBuffer.allocate(43).order(ByteOrder.LITTLE_ENDIAN)
            p.putInt(enabled.toInt()).putInt(enabled.toInt()).putInt((enabled and unhealthy.inv()).toInt())
            p.position(31);p.putInt(extendedUnhealthy.toInt()).putInt(extendedUnhealthy.toInt()).putInt(0)
            return MavFrame(sequence++ and 255,42,1,1,p.array())
        }
        fun ready() {
            engine.connected();engine.foreground(true);engine.onFrame(heartbeat())
            assertTrue(engine.configure(1,ChannelCalibration(verified=true)))
            assertTrue(engine.confirmBenchConditions(true,true,true))
        }
        fun enable() { ready();assertTrue(engine.enableTest());engine.deadman(true) }
        fun send():ControlPermit {
            val p=engine.planOverride();assertNotNull(p);assertTrue(engine.permitValid(p!!));engine.sent(p);return p
        }
    }

    @Test fun noTelemetryOrUnknownArmCannotSend() {
        val r=Rig();assertEquals(ControlState.DISCONNECTED,r.engine.snapshot().state)
        assertFalse(r.engine.enableTest());r.engine.connected();r.engine.foreground(true)
        r.engine.configure(1,ChannelCalibration(verified=true));r.engine.confirmBenchConditions(true,true,true)
        assertFalse(r.engine.enableTest());assertNull(r.engine.planOverride());assertEquals(0L,r.engine.snapshot().overrides)
    }

    @Test fun missingInitialRcDoesNotCreateCircularGate() {
        val r=Rig();r.ready();assertNull(r.engine.snapshot().received)
        assertTrue(r.engine.enableTest());r.engine.deadman(true)
        assertArrayEquals(intArrayOf(1500,65535,65535,65535),r.send().channels)
        assertFalse(r.engine.snapshot().inputVerified)
    }

    @Test fun zeroChannelCountAndRawZeroRemainObservableWithoutProvingApplied() {
        val r=Rig();r.enable();r.engine.onFrame(r.rc(0,0))
        assertEquals(0,r.engine.snapshot().received!![0]);assertNotNull(r.engine.planOverride())
        assertFalse(r.engine.snapshot().feedbackCorrelated);assertFalse(r.engine.snapshot().inputVerified)
    }

    @Test fun separatePhysicalAndOperatorConfirmationsAreMandatory() {
        for(flags in listOf(Triple(false,true,true),Triple(true,false,true),Triple(true,true,false))) {
            val r=Rig();r.ready();assertFalse(r.engine.confirmBenchConditions(flags.first,flags.second,flags.third))
            assertFalse(r.engine.enableTest());assertNull(r.engine.planOverride())
        }
    }

    @Test fun calibrationMustBeExplicitlyVerifiedEachConnection() {
        val r=Rig();r.engine.connected();r.engine.foreground(true);r.engine.onFrame(r.heartbeat())
        r.engine.confirmBenchConditions(true,true,true);assertFalse(r.engine.enableTest())
        r.ready();r.engine.connected();r.engine.onFrame(r.heartbeat());r.engine.confirmBenchConditions(true,true,true)
        assertFalse(r.engine.enableTest());assertFalse(r.engine.snapshot().channels[0].verified)
    }

    @Test fun ch1OnlySmallExcursionNeverTouchesThrottleYawOrAuxiliary() {
        val r=Rig();r.enable()
        for((value,expected) in listOf(-20f to 1480,-1f to 1480,0f to 1500,1f to 1520,20f to 1520)) {
            r.engine.singleChannel(1,value)
            val p=r.send();assertArrayEquals(intArrayOf(expected,65535,65535,65535),p.channels)
            r.clock.now+=50;r.engine.deadman(true)
        }
    }

    @Test fun otherModesNeverGainTransmitRights() {
        for(mode in listOf(0,2,3,4)) {
            val r=Rig();r.ready();r.engine.selectMode(mode);r.engine.confirmBenchConditions(true,true,true)
            assertFalse(r.engine.enableTest());r.engine.deadman(true);assertNull(r.engine.planOverride())
        }
    }

    @Test fun sticksAreOfflinePreviewAndCannotRaiseLiveThrottle() {
        val r=Rig();r.enable();r.engine.sticks(1f,-1f,1f,1f)
        assertArrayEquals(intArrayOf(1900,1100,1900,1900),r.engine.snapshot().previewChannels)
        assertArrayEquals(intArrayOf(1500,65535,65535,65535),r.send().channels)
    }

    @Test fun absoluteLeaseExpiresEvenWithContinuousMatchingFeedback() {
        val r=Rig();r.enable()
        repeat(15) {
            r.engine.deadman(true);r.send();r.engine.onFrame(r.rc(1500));r.clock.now+=50
        }
        r.engine.deadman(true);assertNull(r.engine.planOverride());assertFalse(r.engine.snapshot().enabled)
        assertEquals(15L,r.engine.snapshot().overrides);assertFalse(r.engine.snapshot().inputVerified)
        assertFalse(r.engine.enableTest()) // Requires a fresh explicit physical/operator confirmation.
    }

    @Test fun repeatedEnableCannotRenewActiveLease() {
        val r=Rig();r.enable();r.send()
        repeat(7){r.clock.now+=100;r.engine.deadman(true);assertFalse(r.engine.enableTest())}
        r.clock.now+=50;r.engine.deadman(true);assertNull(r.engine.planOverride())
        assertFalse(r.engine.snapshot().enabled)
    }

    @Test fun feedbackCannotBeReportedAsAcknowledgementOrAppliedProof() {
        val r=Rig();r.enable();r.engine.singleChannel(1,1f);r.send();r.engine.onFrame(r.rc(1520))
        assertTrue(r.engine.snapshot().feedbackCorrelated)
        assertFalse(r.engine.snapshot().inputVerified)
        assertNotEquals(ControlState.RC_INPUT_VERIFIED,r.engine.snapshot().state)
    }

    @Test fun deadmanExpiresAndDoesNotAutomaticallyResume() {
        val r=Rig();r.enable();r.send();r.clock.now+=BenchEngine.DEADMAN_TIMEOUT_MS
        assertNull(r.engine.planOverride());r.engine.deadman(true);assertNull(r.engine.planOverride())
    }

    @Test fun enablingDoesNotStartTransmissionWithoutDeadman() {
        val r=Rig();r.ready();assertTrue(r.engine.enableTest());assertNull(r.engine.planOverride())
        r.clock.now+=BenchEngine.BENCH_START_TIMEOUT_MS;assertNull(r.engine.planOverride());assertFalse(r.engine.snapshot().enabled)
    }

    @Test fun schedulerDoesNotCatchUpOrReplayOldPermits() {
        val r=Rig();r.enable();val p=r.send();assertNull(r.engine.planOverride());assertFalse(r.engine.permitValid(p))
        r.clock.now+=150;r.engine.deadman(true);r.send();assertNull(r.engine.planOverride())
        assertEquals(2L,r.engine.snapshot().overrides)
    }

    @Test fun pendingPermitExpiresBeforeSocketWrite() {
        val r=Rig();r.enable();val p=r.engine.planOverride()!!;r.clock.now+=BenchEngine.SEND_INTERVAL_MS
        assertFalse(r.engine.permitValid(p));assertEquals(0L,r.engine.snapshot().overrides)
    }

    @Test fun changedInputInvalidatesPendingFrame() {
        val r=Rig();r.enable();val p=r.engine.planOverride()!!;r.engine.singleChannel(1,1f)
        assertFalse(r.engine.permitValid(p));assertEquals(1520,r.engine.planOverride()!!.channels[0])
    }

    @Test fun mutationOrForgedThrottleInvalidatesPermit() {
        val r=Rig();r.enable();val p=r.engine.planOverride()!!;p.channels[2]=1900
        assertFalse(r.engine.permitValid(p));assertEquals(0L,r.engine.snapshot().overrides)
    }

    @Test fun stopRevokesAlreadyPlannedFrameAndResetsThrottlePreview() {
        val r=Rig();r.enable();r.engine.sticks(0f,0f,1f,0f);val p=r.engine.planOverride()!!
        r.engine.stop("STOP");assertFalse(r.engine.permitValid(p));assertNull(r.engine.planOverride())
        assertEquals(1100,r.engine.snapshot().previewChannels[2]);assertFalse(r.engine.snapshot().benchConditions.complete)
    }

    @Test fun backgroundAndLostFocusRevokeConfirmationAndPermit() {
        val r=Rig();r.enable();val p=r.engine.planOverride()!!;r.engine.foreground(false)
        assertFalse(r.engine.permitValid(p));r.engine.foreground(true);assertFalse(r.engine.enableTest())
    }

    @Test fun reconnectCannotRestoreOldThrottleInputOrAuthorisation() {
        val r=Rig();r.enable();r.engine.sticks(1f,1f,1f,1f);val p=r.engine.planOverride()!!
        r.engine.disconnected("network lost");r.engine.connected();r.engine.onFrame(r.heartbeat())
        assertFalse(r.engine.permitValid(p));assertNull(r.engine.planOverride());assertFalse(r.engine.enableTest())
        assertArrayEquals(intArrayOf(1500,1500,1100,1500),r.engine.snapshot().previewChannels)
    }

    @Test fun staleHeartbeatStopsRegardlessOfSocketBeingConnected() {
        val r=Rig();r.ready();r.clock.now+=1000;r.engine.confirmBenchConditions(true,true,true)
        assertTrue(r.engine.enableTest());r.engine.deadman(true);r.send();r.clock.now+=500;r.engine.deadman(true)
        assertNull(r.engine.planOverride());assertEquals(ControlState.LINK_LOST,r.engine.snapshot().state)
    }

    @Test fun armedAndUnknownAutopilotStatusStopImmediately() {
        for((armed,status) in listOf(true to 4,false to 0,false to 5)) {
            val r=Rig();r.enable();val p=r.engine.planOverride()!!;r.engine.onFrame(r.heartbeat(armed=armed,status=status))
            assertFalse(r.engine.permitValid(p));assertNull(r.engine.planOverride())
            if(armed)assertEquals(ControlState.ARMED_MONITORING,r.engine.snapshot().state)
        }
    }

    @Test fun crcFaultRequiresNewHeartbeatAndConfirmation() {
        val r=Rig();r.enable();r.engine.streamFault("CRC lỗi");assertEquals(ControlState.FAILSAFE,r.engine.snapshot().state)
        assertFalse(r.engine.enableTest());r.engine.onFrame(r.heartbeat());assertFalse(r.engine.enableTest())
    }

    @Test fun secondAutopilotLocksConnectionUntilReconnect() {
        val r=Rig();r.enable();r.engine.onFrame(r.heartbeat(system=7))
        assertNull(r.engine.planOverride());assertEquals(ControlState.CONTROL_LOCKED,r.engine.snapshot().state)
        r.engine.onFrame(r.heartbeat());r.engine.confirmBenchConditions(true,true,true);assertFalse(r.engine.enableTest())
    }

    @Test fun sameSystemDifferentAutopilotComponentAlsoLocks() {
        val r=Rig();r.enable();r.engine.onFrame(r.heartbeat(component=2))
        assertNull(r.engine.planOverride());assertTrue(r.engine.snapshot().duplicateIdentity)
    }

    @Test fun gcsAndNonArdupilotDoNotBecomeTargets() {
        val r=Rig();r.engine.connected();r.engine.foreground(true)
        listOf(r.heartbeat(system=0),r.heartbeat(system=255),r.heartbeat(component=190,vehicle=6),r.heartbeat(autopilot=12),r.heartbeat(version=2)).forEach(r.engine::onFrame)
        assertNull(r.engine.snapshot().targetSystem)
    }

    @Test fun invalidChannelCountStopsInsteadOfCreatingFeedback() {
        val r=Rig();r.enable();r.engine.onFrame(r.rc(1500,19));assertNull(r.engine.planOverride())
        assertNull(r.engine.snapshot().received)
    }

    @Test fun nonfiniteInputAndBadCalibrationCannotReachWire() {
        val r=Rig();r.enable();r.engine.singleChannel(1,Float.NaN);assertNull(r.engine.planOverride())
        assertFalse(r.engine.configure(1,ChannelCalibration(1500,1100,1900,true)))
        assertFalse(ChannelCalibration(1100,1900,1100).valid())
    }

    @Test fun ch1ExcursionUsesConfiguredTrimAndCannotCrossEndpoint() {
        val r=Rig();r.ready();r.engine.configure(1,ChannelCalibration(1490,1510,1500,true))
        r.engine.confirmBenchConditions(true,true,true);assertTrue(r.engine.enableTest());r.engine.deadman(true)
        r.engine.singleChannel(1,1f);assertEquals(1510,r.send().channels[0])
    }

    @Test fun mode2MappingDeadzoneAndResetAreIndependentFromLiveBench() {
        val m=JoystickMapping()
        assertArrayEquals(floatArrayOf(0f,0f,0f,0f),m.values(),0f)
        m.left(1f,1f,true);m.right(1f,-1f)
        assertArrayEquals(floatArrayOf(1f,-1f,1f,1f),m.values(),0f)
        m.releaseRight();m.releaseLeft();assertArrayEquals(floatArrayOf(0f,0f,1f,0f),m.values(),0f)
        m.reset();assertEquals(0f,m.values()[2],0f)
        m.left(0.03f,1f,false);assertEquals(0f,m.values()[3],0f);assertEquals(0f,m.values()[2],0f)
    }

    @Test fun everyStateHasExplicitOutboundRightsAndNoneAllowsArm() {
        assertEquals(setOf(OutboundKind.GCS_HEARTBEAT,OutboundKind.TELEMETRY_REQUEST,OutboundKind.RC_CH1_BENCH),ControlState.RC_BENCH_ACTIVE.allowedOutbound)
        assertFalse(ControlState.ARMED_MONITORING.allowedOutbound.contains(OutboundKind.RC_CH1_BENCH))
        assertTrue(ControlState.DISCONNECTED.allowedOutbound.isEmpty())
    }

    @Test fun telemetryCarriesPerMessageAgeAndIsClearedOnReconnect() {
        val r=Rig();r.ready();r.engine.onFrame(r.rc());r.clock.now+=100
        r.engine.onFrame(r.heartbeat());r.clock.now+=50
        assertEquals(50L,r.engine.snapshot().telemetryAgeMs["Heartbeat"])
        assertEquals(150L,r.engine.snapshot().telemetryAgeMs["RcChannels"])
        r.engine.disconnected("test");assertTrue(r.engine.snapshot().telemetryAgeMs.isEmpty())
    }

    @Test fun parameterCacheRetainsNamesUpdatesAndNeverAcceptsForeignSender() {
        val r=Rig();r.ready()
        r.engine.onFrame(r.parameter("RC_OVERRIDE_TIME",0.5f));r.engine.onFrame(r.parameter("RC_OPTIONS",33f))
        assertEquals(setOf("RC_OVERRIDE_TIME","RC_OPTIONS"),r.engine.snapshot().parameters.keys)
        r.engine.onFrame(r.parameter("RC_OPTIONS",99f,system=7))
        assertEquals(33f,r.engine.snapshot().parameters["RC_OPTIONS"]!!.value,0f)
        r.engine.onFrame(r.parameter("RC_OPTIONS",34f))
        assertEquals(34f,r.engine.snapshot().parameters["RC_OPTIONS"]!!.value,0f)
        assertEquals(2,r.engine.snapshot().parameters.size)
    }

    @Test fun parameterCacheIsSessionLocalAndBounded() {
        val r=Rig();r.ready();repeat(300){r.engine.onFrame(r.parameter("TEST_$it",it.toFloat()))}
        assertEquals(256,r.engine.snapshot().parameters.size)
        r.engine.disconnected("test");assertTrue(r.engine.snapshot().parameters.isEmpty())
        r.engine.connected();assertTrue(r.engine.snapshot().parameters.isEmpty())
    }

    @Test fun manualDisconnectAndObservedLinkLossHaveDistinctStates() {
        val r=Rig();r.ready();r.engine.disconnected("manual")
        assertEquals(ControlState.DISCONNECTED,r.engine.snapshot().state)
        r.ready();r.engine.disconnected("socket closed",true)
        assertEquals(ControlState.LINK_LOST,r.engine.snapshot().state)
        r.engine.connected();assertEquals(ControlState.CONNECTED_UNVERIFIED,r.engine.snapshot().state)
    }

    @Test fun sequenceGapCountsAllVerifiedTargetFramesEvenWithoutTelemetryDecoder() {
        val r=Rig();r.ready()
        r.clock.now+=10
        r.engine.onFrame(MavFrame(r.sequence++ and 255,42,1,76,ByteArray(33)))
        r.engine.onFrame(r.rc())
        assertEquals(0L,r.engine.snapshot().sequenceGaps)
        assertEquals(r.clock.now,r.engine.snapshot().lastRxMs)
        r.sequence+=2
        r.engine.onFrame(MavFrame(r.sequence++ and 255,42,1,76,ByteArray(33)))
        assertEquals(2L,r.engine.snapshot().sequenceGaps)
    }

    @Test fun lateFramesAfterDisconnectCannotRepopulateTelemetryOrTarget() {
        val r=Rig();r.ready();r.engine.disconnected("manual")
        r.engine.onFrame(r.heartbeat());r.engine.onFrame(r.parameter("RC_OPTIONS",33f));r.engine.onFrame(r.rc())
        assertNull(r.engine.snapshot().targetSystem);assertTrue(r.engine.snapshot().parameters.isEmpty())
        assertTrue(r.engine.snapshot().telemetry.isEmpty())
    }

    @Test fun armFlagAndCriticalSystemHealthAreSeparateFacts() {
        val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=5))
        assertEquals(ArmState.DISARMED,r.engine.snapshot().arm)
        assertEquals(SystemHealth.CRITICAL,r.engine.snapshot().systemHealth)
        assertFalse(r.engine.enableTest());assertNull(r.engine.planOverride())
    }

    @Test fun narrowCriticalReceiverlessBenchNeedsFourthExplicitConsent() {
        val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=5));r.engine.onFrame(r.sysStatus())
        assertTrue(r.engine.snapshot().criticalRcDiagnosticEligible)
        assertFalse(r.engine.enableTest())
        assertTrue(r.engine.confirmBenchConditions(true,true,true,true));assertTrue(r.engine.enableTest())
        r.engine.deadman(true);r.engine.singleChannel(1,1f)
        assertArrayEquals(intArrayOf(1520,65535,65535,65535),r.send().channels)
        assertTrue(r.engine.snapshot().criticalRcDiagnosticActive)
        assertEquals(SystemHealth.CRITICAL,r.engine.snapshot().systemHealth)
        assertFalse(r.engine.snapshot().inputVerified)
    }

    @Test fun criticalDiagnosticRejectsMissingOrStaleSysStatusAndOtherSensorFaults() {
        val cases=listOf<Pair<Long?,Long>>(null to 0L,0x10010001L to 0L,0x10000000L to 0L,0L to 0L,0x10010000L to 1L)
        for((unhealthy,extended) in cases) {
            val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=5))
            if(unhealthy!=null)r.engine.onFrame(r.sysStatus(unhealthy,extended))
            r.engine.confirmBenchConditions(true,true,true,true)
            assertFalse(r.engine.enableTest());assertNull(r.engine.planOverride())
        }
        val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=5));r.engine.onFrame(r.sysStatus())
        r.clock.now+=BenchEngine.SYS_STATUS_TIMEOUT_MS;r.engine.onFrame(r.heartbeat(status=5))
        r.engine.confirmBenchConditions(true,true,true,true);assertFalse(r.engine.enableTest())
    }

    @Test fun criticalDiagnosticRejectsExistingRcUntilNewIsolatedReceiverlessSession() {
        val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=5));r.engine.onFrame(r.sysStatus())
        r.engine.onFrame(r.rc(1500));r.engine.confirmBenchConditions(true,true,true,true)
        assertFalse(r.engine.snapshot().criticalRcDiagnosticEligible);assertFalse(r.engine.enableTest())
    }

    @Test fun criticalDiagnosticFeedbackLatchNeverExtendsLifetimeOrOpensOtherChannels() {
        val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=5));r.engine.onFrame(r.sysStatus())
        r.engine.confirmBenchConditions(true,true,true,true);assertTrue(r.engine.enableTest());r.engine.deadman(true)
        r.engine.singleChannel(1,1f)
        repeat(15){
            r.engine.deadman(true);assertArrayEquals(intArrayOf(1520,65535,65535,65535),r.send().channels)
            r.engine.onFrame(r.rc(1520));r.clock.now+=50
        }
        r.engine.deadman(true);assertNull(r.engine.planOverride());assertFalse(r.engine.snapshot().enabled)
        assertFalse(r.engine.snapshot().benchConditions.criticalRcDiagnosticAuthorised)
        assertFalse(r.engine.snapshot().inputVerified)
    }

    @Test fun additionalHealthFaultDuringCriticalLeaseRevokesBeforeNextWrite() {
        val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=5));r.engine.onFrame(r.sysStatus())
        r.engine.confirmBenchConditions(true,true,true,true);assertTrue(r.engine.enableTest());r.engine.deadman(true)
        val p=r.engine.planOverride()!!;r.engine.onFrame(r.sysStatus(0x10010001L))
        assertFalse(r.engine.permitValid(p));assertFalse(r.engine.snapshot().enabled)
    }

    @Test fun newCriticalTransitionCannotUsePreauthorisedHealthyLease() {
        val r=Rig();r.ready();r.engine.confirmBenchConditions(true,true,true,true);assertTrue(r.engine.enableTest())
        r.engine.deadman(true);r.engine.onFrame(r.sysStatus());r.engine.onFrame(r.heartbeat(status=5))
        assertNull(r.engine.planOverride());assertFalse(r.engine.snapshot().enabled)
    }

    @Test fun bootEmergencyPoweroffAndUnknownHealthStayLockedEvenWithFourthConsent() {
        for(status in listOf(0,1,2,6,7,8,9,255)) {
            val r=Rig();r.ready();r.engine.onFrame(r.heartbeat(status=status));r.engine.onFrame(r.sysStatus())
            r.engine.confirmBenchConditions(true,true,true,true)
            assertEquals(ArmState.DISARMED,r.engine.snapshot().arm)
            assertFalse(r.engine.enableTest());assertNull(r.engine.planOverride())
        }
    }
}
