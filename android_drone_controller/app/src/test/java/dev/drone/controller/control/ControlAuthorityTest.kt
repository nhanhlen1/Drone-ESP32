package dev.drone.controller.control

import org.junit.Assert.*
import org.junit.Test

/** All qualification records here are test doubles, not hardware qualification. */
class ControlAuthorityTest {
    private class Clock(var now:Long=10_000):MonotonicClock { override fun nowMs()=now }
    private class Rig(defaultProvider:Boolean=false) {
        val clock=Clock()
        var qualification:FlightQualification?=null
        val authority=if(defaultProvider)ControlAuthority(clock) else ControlAuthority(clock,FlightQualificationProvider{_,_->qualification})
        val id=AuthorityIdentity(42,1)
        var epoch=0L
        val neutral=listOf(1500,1500,1100,1500)
        var params:AuthorityRcParameters?=null
        fun ready(mode:AuthorityMode=AuthorityMode.BENCH) {
            epoch=authority.beginConnection(true);assertTrue(authority.tcpConnected(epoch));authority.foreground(true);authority.selectMode(mode)
            authority.observeHeartbeat(epoch,id,false,SystemHealth.STANDBY,clock.now)
            params=AuthorityRcParameters(epoch,id,clock.now,255,0,33,.5f,List(4){ChannelCalibration(verified=true)},true)
            authority.observeRcParameters(params!!)
            authority.input(neutral)
        }
        fun qualify() {
            qualification=FlightQualification(id,epoch,"SIMULATION-TEST-DOUBLE",clock.now+60_000,
                softwareTested=true,sitlTested=true,hardwareRcInputVerified=true,overrideTimeoutVerified=true,
                linkLossFailsafeVerified=true,independentStopVerified=true,powertrainMappingVerified=true,operatorQualificationApproved=true)
            authority.observeRcEcho(epoch,id,neutral,clock.now)
        }
        fun bench() {ready();assertTrue(authority.enableBench(AuthorityBenchConditions(true,true,true)));authority.deadman(true)}
        fun flight() {ready(AuthorityMode.FLIGHT_CANDIDATE);qualify();assertTrue(authority.enableFlight(true));authority.deadman(true)}
        fun heartbeat(armed:Boolean=false,health:SystemHealth=SystemHealth.STANDBY) {
            clock.now++;authority.observeHeartbeat(epoch,id,armed,health,clock.now)
        }
        fun written():AuthorityDecision {
            val d=authority.decision();assertTrue(d.canTransmit)
            authority.frameWritten(d.connectionEpoch,d.authorityEpoch,d.channels)
            return d
        }
    }

    @Test fun defaultProductionProviderNeverQualifiesFlight() {
        val r=Rig(true);r.ready(AuthorityMode.FLIGHT_CANDIDATE);r.qualify()
        assertFalse(r.authority.enableFlight(true));r.authority.deadman(true)
        assertFalse(r.authority.decision().canTransmit)
        assertTrue(r.authority.decision().reason.contains("KIỂM ĐỊNH BAY"))
    }
    @Test fun initialAndReadOnlyStatesCannotEmitRc() {
        val r=Rig();assertEquals(AuthorityStage.DISCONNECTED,r.authority.decision().stage)
        r.ready(AuthorityMode.READ_ONLY);r.qualify()
        assertFalse(r.authority.enableFlight(true));assertFalse(r.authority.enableBench(AuthorityBenchConditions(true,true,true)))
        r.authority.deadman(true);assertFalse(r.authority.decision().canTransmit)
    }
    @Test fun wifiTcpHeartbeatParameterStagesFailClosedInOrder() {
        val r=Rig();val wrong=r.authority.beginConnection(false)
        assertFalse(r.authority.tcpConnected(wrong));assertEquals(AuthorityStage.LOCKED,r.authority.decision().stage)
        val epoch=r.authority.beginConnection(true);assertEquals(AuthorityStage.WIFI_BOUND,r.authority.decision().stage)
        assertFalse(r.authority.tcpConnected(wrong));assertTrue(r.authority.tcpConnected(epoch))
        assertEquals(AuthorityStage.TCP_CONNECTED,r.authority.decision().stage)
        assertFalse(r.authority.tcpConnected(epoch))
        r.authority.observeHeartbeat(epoch,r.id,false,SystemHealth.STANDBY,r.clock.now)
        assertEquals(AuthorityStage.FC_HEARTBEAT_VERIFIED,r.authority.decision().stage)
        r.authority.selectMode(AuthorityMode.BENCH)
        assertFalse(r.authority.enableBench(AuthorityBenchConditions(true,true,true)))
    }
    @Test fun benchBootstrapDoesNotRequireInitialRcEcho() {
        val r=Rig();r.bench()
        assertTrue(r.authority.decision().canTransmit)
        assertFalse(r.authority.decision().rcEchoVerified)
        assertEquals(listOf(1500,65535,65535,65535),r.written().channels)
    }
    @Test fun bench1470And1530UseOnlyCh1AndFreshEchoAfterWrite() {
        val r=Rig();r.bench()
        for(value in listOf(1470,1500,1530)) {
            r.authority.input(listOf(value,1500,1100,1500));val d=r.written()
            assertEquals(listOf(value,65535,65535,65535),d.channels)
            r.clock.now++;r.authority.observeRcEcho(r.epoch,r.id,listOf(value,0,0,0),r.clock.now)
            assertTrue(r.authority.decision().rcEchoVerified)
            assertEquals(AuthorityStage.RC_ECHO_VERIFIED,r.authority.decision().stage)
        }
    }
    @Test fun heartbeatAndUnwrittenMatchingEchoNeverProveWrittenRc() {
        val r=Rig();r.bench();r.heartbeat()
        r.authority.observeRcEcho(r.epoch,r.id,r.neutral,r.clock.now)
        assertFalse(r.authority.decision().rcEchoVerified)
        r.written();r.clock.now++
        r.authority.observeRcEcho(r.epoch,AuthorityIdentity(43,1),r.neutral,r.clock.now)
        assertFalse(r.authority.decision().rcEchoVerified)
    }
    @Test fun delayedEchoForAnOlderInputCannotVerifyTheCurrentRequestedValue() {
        val r=Rig();r.bench();r.written();r.clock.now++
        r.authority.observeRcEcho(r.epoch,r.id,r.neutral,r.clock.now)
        assertTrue(r.authority.decision().rcEchoVerified)
        r.authority.input(listOf(1470,1500,1100,1500));assertFalse(r.authority.decision().rcEchoVerified)
        r.clock.now++;r.authority.observeRcEcho(r.epoch,r.id,r.neutral,r.clock.now)
        assertFalse(r.authority.decision().rcEchoVerified)
        r.written();r.clock.now++
        r.authority.observeRcEcho(r.epoch,r.id,listOf(1470,1500,1100,1500),r.clock.now)
        assertTrue(r.authority.decision().rcEchoVerified)
    }
    @Test fun benchRequiresEveryPhysicalAndOperatorCondition() {
        for(c in listOf(AuthorityBenchConditions(false,true,true),AuthorityBenchConditions(true,false,true),AuthorityBenchConditions(true,true,false))) {
            val r=Rig();r.ready();assertFalse(r.authority.enableBench(c));assertFalse(r.authority.decision().canTransmit)
        }
    }
    @Test fun unexpectedArmedRevokesBenchAndRequestsOnlyBenchRelease() {
        val r=Rig();r.bench();r.written();r.heartbeat(true)
        assertFalse(r.authority.decision().canTransmit)
        assertEquals(AuthorityStopEffect.RELEASE_BENCH_CHANNELS,r.authority.decision().stopEffect)
        r.heartbeat(false);r.authority.deadman(true)
        assertFalse(r.authority.decision().canTransmit)
    }
    @Test fun fullyQualifiedFlightContinuesAcrossExpectedArmedTransition() {
        val r=Rig();r.flight();val before=r.written()
        r.authority.observeRcEcho(r.epoch,r.id,r.neutral,r.clock.now+0)
        r.heartbeat(true,SystemHealth.ACTIVE)
        r.authority.input(listOf(1540,1460,1200,1540));r.authority.deadman(true)
        val active=r.authority.decision()
        assertTrue(active.canTransmit);assertEquals(before.authorityEpoch,active.authorityEpoch)
        assertEquals(AuthorityStage.ARMED_CONTROL_CANDIDATE,active.stage)
        assertEquals(listOf(1540,1460,1200,1540),active.channels)
        assertFalse(active.rcEchoVerified) // A disarmed observation cannot remain verified after ARM.
        r.written();r.clock.now++
        r.authority.observeRcEcho(r.epoch,r.id,active.channels,r.clock.now)
        assertTrue(r.authority.decision().rcEchoVerified)
    }
    @Test fun flightCannotBeEnabledByQualificationAloneOrWhileAlreadyArmed() {
        val r=Rig();r.ready(AuthorityMode.FLIGHT_CANDIDATE);r.qualify()
        assertFalse(r.authority.enableFlight(false))
        r.heartbeat(true);assertFalse(r.authority.enableFlight(true))
        assertFalse(r.authority.decision().canTransmit)
    }
    @Test fun everyIndependentQualificationRecordIsRequired() {
        val baseline=Rig().apply{ready(AuthorityMode.FLIGHT_CANDIDATE);qualify()}.qualification!!
        val invalid=listOf(
            baseline.copy(softwareTested=false),baseline.copy(sitlTested=false),baseline.copy(hardwareRcInputVerified=false),
            baseline.copy(overrideTimeoutVerified=false),baseline.copy(linkLossFailsafeVerified=false),baseline.copy(independentStopVerified=false),
            baseline.copy(powertrainMappingVerified=false),baseline.copy(operatorQualificationApproved=false),baseline.copy(evidenceReference=""),
            baseline.copy(connectionEpoch=999),baseline.copy(identity=AuthorityIdentity(43,1)),baseline.copy(validUntilMs=10_000),
        )
        for(record in invalid) {
            val r=Rig();r.ready(AuthorityMode.FLIGHT_CANDIDATE);r.qualify();r.qualification=record
            assertFalse(record.toString(),r.authority.enableFlight(true))
        }
    }
    @Test fun flightRequiresFreshRealRcAndDoesNotUseSocketWriteAsEcho() {
        val r=Rig();r.ready(AuthorityMode.FLIGHT_CANDIDATE);r.qualify()
        r.clock.now+=ControlAuthority.RC_ECHO_FRESH_MS
        r.heartbeat();assertFalse(r.authority.enableFlight(true))
    }
    @Test fun parameterReadbackChecksSenderOverrideBitTimeoutCalibrationAndMapping() {
        val r=Rig();r.ready();val p=r.params!!
        val invalid=listOf(p.copy(gcsSystemLow=254),p.copy(rcOptions=35),p.copy(overrideTimeoutSeconds=0f),p.copy(overrideTimeoutSeconds=-1f),
            p.copy(overrideTimeoutSeconds=Float.NaN),p.copy(overrideTimeoutSeconds=3f),p.copy(mappingVerified=false),
            p.copy(channels=List(4){ChannelCalibration(verified=false)}),p.copy(channels=emptyList()),p.copy(channels=listOf(ChannelCalibration(verified=true))))
        for(value in invalid) {
            val x=Rig();x.ready();x.authority.observeRcParameters(value)
            assertFalse(value.toString(),x.authority.enableBench(AuthorityBenchConditions(true,true,true)))
        }
    }
    @Test fun configuredGcsIdRangeIsHandledWithoutRequiringHigh255() {
        val r=Rig();r.ready();r.authority.observeRcParameters(r.params!!.copy(gcsSystemLow=250,gcsSystemHigh=255))
        assertTrue(r.authority.enableBench(AuthorityBenchConditions(true,true,true)))
    }
    @Test fun changingParametersRevokesAnActiveFlightAuthority() {
        val r=Rig();r.flight();r.heartbeat(true,SystemHealth.ACTIVE);r.written()
        r.clock.now++;r.authority.observeRcParameters(r.params!!.copy(receivedAtMs=r.clock.now,rcOptions=35))
        assertFalse(r.authority.decision().canTransmit)
        assertEquals(AuthorityStopEffect.STOP_RC_TRANSMISSION,r.authority.decision().stopEffect)
    }
    @Test fun reconnectResetsThrottleEchoIdentityAndRejectsOldCallbacksAndQualification() {
        val r=Rig();r.flight();r.heartbeat(true,SystemHealth.ACTIVE);r.authority.input(listOf(1540,1460,1800,1540));r.written()
        val oldEpoch=r.epoch;r.authority.disconnect();r.epoch=r.authority.beginConnection(true);r.authority.tcpConnected(r.epoch)
        r.authority.observeHeartbeat(oldEpoch,r.id,true,SystemHealth.ACTIVE,r.clock.now+0)
        assertNull(r.authority.decision().target);assertFalse(r.authority.decision().canTransmit)
        r.heartbeat(false);r.authority.observeRcParameters(r.params!!.copy(connectionEpoch=r.epoch,receivedAtMs=r.clock.now))
        r.authority.observeRcEcho(oldEpoch,r.id,listOf(1540,1460,1800,1540),r.clock.now)
        assertFalse(r.authority.decision().rcEchoVerified)
        assertEquals(r.neutral,r.authority.decision().channels)
        assertFalse(r.authority.enableFlight(true))
    }
    @Test fun stopAndBackgroundDoNotSetMinimumThrottleOrDisarmFlightOnWire() {
        for(background in listOf(false,true)) {
            val r=Rig();r.flight();r.heartbeat(true,SystemHealth.ACTIVE);r.authority.input(listOf(1500,1500,1700,1500));r.written()
            if(background)r.authority.foreground(false) else r.authority.stop()
            val d=r.authority.decision();assertFalse(d.canTransmit)
            assertEquals(AuthorityStopEffect.STOP_RC_TRANSMISSION,d.stopEffect)
            r.authority.foreground(true);r.authority.deadman(true);assertFalse(r.authority.decision().canTransmit)
        }
    }
    @Test fun benchExpiryIsAbsoluteDespiteRepeatedEchoAndInputRenewals() {
        val r=Rig();r.bench();val first=r.written();val began=r.clock.now
        for(offset in 50L..750L step 50) {
            r.clock.now=began+offset;r.authority.input(r.neutral);r.authority.deadman(true)
            if(offset<750) {
                r.authority.observeHeartbeat(r.epoch,r.id,false,SystemHealth.STANDBY,r.clock.now)
                r.authority.observeRcEcho(r.epoch,r.id,r.neutral,r.clock.now);r.written()
            }
        }
        assertFalse(r.authority.decision().canTransmit)
        assertTrue(r.authority.decision().authorityEpoch>first.authorityEpoch)
    }
    @Test fun deadmanAndInputWatchdogsStopStuckCommands() {
        val r=Rig();r.flight();r.written();r.clock.now+=200
        r.authority.input(r.neutral) // Input alone does not renew deadman.
        assertFalse(r.authority.decision().canTransmit)
        val x=Rig();x.flight();x.written();x.clock.now+=199;x.authority.deadman(true);x.clock.now++
        assertFalse(x.authority.decision().canTransmit) // Deadman alone does not renew input.
    }
    @Test fun heartbeatEchoHealthAndQualificationWatchdogsAreIndependent() {
        for(fault in listOf("heartbeat","echo","health","approval")) {
            val r=Rig();r.flight();r.written()
            when(fault) {
                "heartbeat"->{
                    repeat(30){
                        r.clock.now+=50;r.authority.input(r.neutral);r.authority.deadman(true)
                        r.authority.observeRcEcho(r.epoch,r.id,r.neutral,r.clock.now)
                    }
                    assertTrue(r.authority.decision().reason.contains("HEARTBEAT"))
                }
                "echo"->{
                    repeat(24){
                        r.clock.now+=50;r.authority.input(r.neutral);r.authority.deadman(true)
                        r.authority.observeHeartbeat(r.epoch,r.id,false,SystemHealth.STANDBY,r.clock.now)
                    }
                    assertTrue(r.authority.decision().reason.contains("Phản hồi RC"))
                }
                "health"->r.heartbeat(false,SystemHealth.CRITICAL)
                else->{r.qualification=null}
            }
            r.authority.input(r.neutral);r.authority.deadman(true)
            assertFalse(fault,r.authority.decision().canTransmit)
        }
    }
    @Test fun duplicateIdentityLocksUntilASeparateConnection() {
        val r=Rig();r.flight();r.clock.now++
        r.authority.observeHeartbeat(r.epoch,AuthorityIdentity(43,1),false,SystemHealth.STANDBY,r.clock.now)
        assertFalse(r.authority.decision().canTransmit)
        r.heartbeat();assertFalse(r.authority.enableFlight(true))
    }
    @Test fun malformedAndFutureObservationsDoNotRefreshAuthority() {
        val r=Rig();r.ready()
        r.authority.observeHeartbeat(r.epoch,r.id,false,SystemHealth.ACTIVE,r.clock.now+50_000)
        r.clock.now+=1500;assertFalse(r.authority.enableBench(AuthorityBenchConditions(true,true,true)))
        val x=Rig();x.ready(AuthorityMode.FLIGHT_CANDIDATE);x.qualify()
        x.authority.observeRcEcho(x.epoch,x.id,listOf(1,2,3),x.clock.now+0)
        assertTrue(x.authority.enableFlight(true)) // Malformed echo never replaced the prior good sample.
    }
    @Test fun rejectedHighThrottleBeforeEnableCannotBeSilentlyTreatedAsMin() {
        val r=Rig();r.ready(AuthorityMode.FLIGHT_CANDIDATE);r.qualify()
        r.authority.input(listOf(1500,1500,1900,1500));assertFalse(r.authority.enableFlight(true))
        assertFalse("Repeated enable must not erase the rejected input",r.authority.enableFlight(true))
        r.authority.input(r.neutral);assertTrue(r.authority.enableFlight(true))
    }
    @Test fun disarmObservedAfterFlightRevokesRatherThanAutomaticallyRearming() {
        val r=Rig();r.flight();r.heartbeat(true,SystemHealth.ACTIVE);assertTrue(r.authority.decision().canTransmit)
        r.heartbeat(false,SystemHealth.STANDBY);assertFalse(r.authority.decision().canTransmit)
        r.heartbeat(true,SystemHealth.ACTIVE);r.authority.deadman(true);assertFalse(r.authority.decision().canTransmit)
    }
    @Test fun modeSwitchCancelsAuthorityAndClearsThrottleAndEvidence() {
        val r=Rig();r.flight();r.heartbeat(true,SystemHealth.ACTIVE);r.authority.input(listOf(1540,1460,1800,1540));r.written()
        r.authority.selectMode(AuthorityMode.READ_ONLY)
        assertFalse(r.authority.decision().canTransmit);assertEquals(r.neutral,r.authority.decision().channels)
        assertFalse(r.authority.decision().rcEchoVerified)
    }
    @Test fun transitionLogIsBoundedAndHeartbeatCannotAutomaticallyGrantControl() {
        val r=Rig();r.ready()
        repeat(200){r.heartbeat()}
        assertTrue(r.authority.decision().transitions.size<=64)
        assertFalse(r.authority.decision().canTransmit)
    }
}
