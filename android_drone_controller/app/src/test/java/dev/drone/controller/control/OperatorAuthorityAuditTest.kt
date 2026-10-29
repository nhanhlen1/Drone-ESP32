package dev.drone.controller.control

import org.junit.Assert.*
import org.junit.Test

/** Adversarial review of the production commissioning profiles. No hardware
 * certificate is injected and these tests never encode or transmit ARM. */
class OperatorAuthorityAuditTest {
    private class Clock(var now:Long=10_000L):MonotonicClock { override fun nowMs()=now }
    private class Rig(val mode:AuthorityMode=AuthorityMode.OPERATOR_CONTROL) {
        val clock=Clock()
        val authority=ControlAuthority(clock)
        val identity=AuthorityIdentity(42,1)
        val neutral=listOf(1500,1500,1100,1500)
        var epoch=0L
        fun ready(health:SystemHealth=SystemHealth.STANDBY) {
            epoch=authority.beginConnection(true)
            assertTrue(authority.tcpConnected(epoch))
            authority.foreground(true);authority.selectMode(mode)
            heartbeat(false,health)
            authority.observeRcParameters(AuthorityRcParameters(epoch,identity,clock.now,255,0,33,.5f,
                List(4){ChannelCalibration(verified=true)},true))
            authority.input(neutral)
        }
        fun heartbeat(armed:Boolean=false,health:SystemHealth=if(armed)SystemHealth.ACTIVE else SystemHealth.STANDBY) {
            authority.observeHeartbeat(epoch,identity,armed,health,clock.now)
        }
        fun enable():Boolean=authority.enableOperatorSession(true,mode==AuthorityMode.BENCH_4CH,true)
        fun pulse(values:List<Int> = neutral) { authority.input(values);authority.deadman(true) }
        fun written():AuthorityDecision {
            val d=authority.decision();assertTrue(d.reason,d.canTransmit)
            authority.frameWritten(d.connectionEpoch,d.authorityEpoch,d.channels)
            return d
        }
        fun echo(values:List<Int> = neutral) {authority.observeRcEcho(epoch,identity,values,clock.now)}
    }

    @Test fun explicitBenchControlsAllFourChannelsWithoutCreatingAFlightQualification() {
        val r=Rig(AuthorityMode.BENCH_4CH);r.ready()
        assertTrue(r.enable());r.pulse(listOf(1470,1530,1270,1430))
        val d=r.written()
        assertEquals(listOf(1470,1530,1270,1430),d.channels)
        assertEquals(AuthorityMode.BENCH_4CH,d.mode)
        assertFalse(d.rcEchoVerified)
        r.clock.now++;r.echo(d.channels)
        assertTrue(r.authority.decision().rcEchoVerified)
    }

    @Test fun everyBenchConsentIsRequiredAndNoneSurvivesReconnect() {
        for(consent in listOf(Triple(false,true,true),Triple(true,false,true),Triple(true,true,false))) {
            val r=Rig(AuthorityMode.BENCH_4CH);r.ready()
            assertFalse(r.authority.enableOperatorSession(consent.first,consent.second,consent.third))
            r.pulse();assertFalse(r.authority.decision().canTransmit)
        }
        val r=Rig(AuthorityMode.BENCH_4CH);r.ready();assertTrue(r.enable());r.pulse();r.written()
        r.authority.disconnect();r.clock.now++;r.ready();r.pulse()
        assertFalse(r.authority.decision().canTransmit)
        assertEquals(r.neutral,r.authority.decision().channels)
    }

    @Test fun controlKeepsAuthorityAcrossArmedTransitionButBenchRevokes() {
        val control=Rig();control.ready();assertTrue(control.enable());control.pulse();val before=control.written()
        control.clock.now++;control.echo();control.clock.now++;control.heartbeat(true)
        control.pulse(listOf(1540,1460,1210,1530))
        val armed=control.written()
        assertEquals(before.authorityEpoch,armed.authorityEpoch)
        assertEquals(AuthorityStage.ARMED_CONTROL_CANDIDATE,armed.stage)
        assertEquals(listOf(1540,1460,1210,1530),armed.channels)
        val bench=Rig(AuthorityMode.BENCH_4CH);bench.ready();assertTrue(bench.enable());bench.pulse();bench.written()
        bench.clock.now++;bench.heartbeat(true);bench.pulse()
        assertFalse(bench.authority.decision().canTransmit)
        bench.clock.now++;bench.heartbeat(false);bench.pulse()
        assertFalse("A later DISARMED must not resurrect bench authority",bench.authority.decision().canTransmit)
    }

    @Test fun raisingThrottleWhileDisarmedControlRevokesInsteadOfRememberingItUntilArm() {
        val r=Rig();r.ready();assertTrue(r.enable());r.pulse();r.written()
        r.pulse(listOf(1500,1500,1700,1500))
        assertFalse(r.authority.decision().canTransmit)
        r.clock.now++;r.heartbeat(true);r.pulse()
        assertFalse(r.authority.decision().canTransmit)
        assertEquals(r.neutral,r.authority.decision().channels)
    }

    @Test fun aFreshUiPulseCannotResurrectAuthorityAfterTheTwoHundredMillisecondDeadline() {
        val r=Rig();r.ready();assertTrue(r.enable());r.pulse();r.written()
        r.clock.now+=200L
        r.pulse() // Input arriving at the deadline must not revive an expired deadman.
        assertFalse(r.authority.decision().canTransmit)
        r.clock.now++;r.heartbeat();r.echo();r.pulse()
        assertFalse(r.authority.decision().canTransmit)
    }

    @Test fun rcEchoDeadlineIsIndependentOfFreshHeartbeatAndUiInput() {
        val r=Rig();r.ready();assertTrue(r.enable());r.pulse();r.written();r.echo()
        repeat(24) {
            r.clock.now+=50L;r.heartbeat();r.pulse()
            if(it<23)r.written()
        }
        assertFalse(r.authority.decision().canTransmit)
        assertTrue(r.authority.decision().reason.contains("Phản hồi RC"))
    }

    @Test fun fourChannelBenchLeaseCannotBeExtendedByContinuousInputOrEcho() {
        val r=Rig(AuthorityMode.BENCH_4CH);r.ready();assertTrue(r.enable());r.pulse();r.written()
        repeat(100) {
            r.clock.now+=50L;r.heartbeat();r.pulse();r.echo()
            if(it<99)r.written()
        }
        assertFalse(r.authority.decision().canTransmit)
    }

    @Test fun receiverlessCriticalExceptionIsUnavailableToControlProfile() {
        val r=Rig();r.ready(SystemHealth.CRITICAL);r.authority.receiverlessCriticalPattern(true)
        assertFalse(r.enable());r.pulse()
        assertFalse(r.authority.decision().canTransmit)
    }

    @Test fun receiverlessCriticalPatternCannotSurviveANewConnection() {
        val r=Rig(AuthorityMode.BENCH_4CH);r.ready(SystemHealth.CRITICAL)
        r.authority.receiverlessCriticalPattern(true);assertTrue(r.enable());r.pulse();r.written()
        r.authority.disconnect();r.clock.now++;r.ready(SystemHealth.CRITICAL)
        assertFalse(r.enable())
    }

    @Test fun receiverlessCriticalPatternExpiresEvenWhenHeartbeatAndInputRemainFresh() {
        val r=Rig(AuthorityMode.BENCH_4CH);r.ready(SystemHealth.CRITICAL)
        r.authority.receiverlessCriticalPattern(true);assertTrue(r.enable());r.pulse();r.written()
        repeat(30) {
            r.clock.now+=50L;r.heartbeat(false,SystemHealth.CRITICAL);r.pulse()
            if(it<29)r.written()
        }
        assertFalse(r.authority.decision().canTransmit)
    }
}
