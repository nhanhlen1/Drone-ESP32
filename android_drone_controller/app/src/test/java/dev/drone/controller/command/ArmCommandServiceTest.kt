package dev.drone.controller.command

import dev.drone.controller.mavlink.Telemetry
import org.junit.Assert.*
import org.junit.Test

class ArmCommandServiceTest {
    private class Rig(approved:Boolean=true) {
        var time=10000L
        var approved=approved
        val intents=mutableListOf<ArmCommandIntent>()
        val service=ArmCommandService({time},HardwareTierApproval{this.approved},ArmIntentSink{intents.add(it);true})
        fun evidence(armed:Boolean?=false)=ArmTelemetryEvidence(42,1,time,armed,true,true,true)
        fun request(armed:Boolean=true,evidence:ArmTelemetryEvidence=evidence())=service.request(armed,true,evidence)
        fun ack(result:Int=0,command:Int=400,targetSystem:Int=255,targetComponent:Int=190,system:Int=42,component:Int=1) {
            service.onAck(system,component,Telemetry.CommandAck(command,result,0,0,targetSystem,targetComponent))
        }
        fun heartbeat(armed:Boolean?,at:Long=time)=service.onHeartbeat(42,1,armed,at)
    }
    @Test fun productionDefaultsNeverOfferEvenWhenEvidenceAndConfirmationArePresent() {
        var called=0
        val service=ArmCommandService({10000},sink=ArmIntentSink{called++;true})
        val result=service.request(true,true,ArmTelemetryEvidence(42,1,10000,false,true,true,true))
        assertTrue(result is ArmRequestResult.Rejected)
        assertEquals(0,called);assertEquals(ArmCommandStatus.IDLE,service.snapshot().status)
    }
    @Test fun eachArmPrerequisiteBlocksBeforeCreatingAnIntent() {
        val r=Rig();val e=r.evidence()
        val bad=listOf(e.copy(heartbeatAtMs=r.time-1500),e.copy(heartbeatAtMs=r.time+1),e.copy(armed=null),
            e.copy(rcInputVerified=false),e.copy(preArmClear=false),e.copy(onGroundVerified=false),
            e.copy(systemId=0),e.copy(componentId=0),e.copy(armed=true))
        for(evidence in bad)assertTrue(r.request(evidence=evidence) is ArmRequestResult.Rejected)
        assertTrue(r.service.request(true,false,e) is ArmRequestResult.Rejected)
        assertTrue(r.intents.isEmpty())
    }
    @Test fun semanticIntentIsNotSocketOrFcEvidence() {
        val r=Rig()
        assertTrue(r.request() is ArmRequestResult.Accepted)
        val s=r.service.snapshot()
        assertEquals(1,r.intents.size);assertEquals(400,s.intent!!.command)
        assertEquals(42,s.intent.targetSystem);assertEquals(1,s.intent.targetComponent)
        assertTrue(s.intent.desiredArmed);assertTrue(s.intentAccepted)
        assertNull(s.ackResult);assertFalse(s.heartbeatConfirmed)
        assertEquals(ArmCommandStatus.INTENT_ACCEPTED,s.status)
    }
    @Test fun ackAloneNeverConfirmsArmAndRequiresLaterMatchingHeartbeat() {
        val r=Rig();r.request();r.time++;r.ack()
        assertEquals(ArmCommandStatus.ACK_ACCEPTED_WAITING_HEARTBEAT,r.service.snapshot().status)
        assertFalse(r.service.snapshot().heartbeatConfirmed)
        r.heartbeat(true) // same timestamp as ACK is not later evidence
        assertFalse(r.service.snapshot().heartbeatConfirmed)
        r.time++;r.heartbeat(false)
        assertFalse(r.service.snapshot().heartbeatConfirmed)
        r.time++;r.heartbeat(true)
        assertEquals(ArmCommandStatus.CONFIRMED,r.service.snapshot().status)
        assertTrue(r.service.snapshot().heartbeatConfirmed)
    }
    @Test fun observedStateWithoutAckCannotBeAttributedToAppCommand() {
        val r=Rig();r.request();r.time++;r.heartbeat(true)
        val s=r.service.snapshot()
        assertEquals(true,s.observedArmed);assertNull(s.ackResult)
        assertFalse(s.heartbeatConfirmed);assertEquals(ArmCommandStatus.INTENT_ACCEPTED,s.status)
        r.time++;r.ack();assertFalse(r.service.snapshot().heartbeatConfirmed)
    }
    @Test fun ackCorrelationRejectsWrongSourceCommandOrGcsDestination() {
        val r=Rig();r.request()
        r.ack(system=43);r.ack(component=2);r.ack(command=511);r.ack(targetSystem=254);r.ack(targetComponent=1)
        assertNull(r.service.snapshot().ackResult)
        r.ack(targetSystem=0,targetComponent=0) // MAVLink1-length ACK has absent extensions
        assertEquals(0,r.service.snapshot().ackResult)
    }
    @Test fun deniedUnsupportedFailedCancelledAndUnknownResponsesAreTerminal() {
        val cases=mapOf(1 to ArmCommandStatus.DENIED,2 to ArmCommandStatus.DENIED,3 to ArmCommandStatus.UNSUPPORTED,
            4 to ArmCommandStatus.FAILED,6 to ArmCommandStatus.CANCELLED,99 to ArmCommandStatus.FAILED)
        for((result,expected) in cases) {
            val r=Rig();r.request();r.ack(result)
            assertEquals(expected,r.service.snapshot().status)
            r.time++;r.ack(0);r.time++;r.heartbeat(true)
            assertEquals(expected,r.service.snapshot().status)
            assertEquals(1,r.intents.size)
        }
    }
    @Test fun progressNeverExtendsAbsoluteTimeoutAndLateAckCannotRevive() {
        val r=Rig();r.request();r.time+=2999;r.ack(5)
        assertEquals(ArmCommandStatus.IN_PROGRESS,r.service.snapshot().status)
        r.time++;r.ack(5)
        assertEquals(ArmCommandStatus.TIMED_OUT,r.service.snapshot().status)
        r.time++;r.ack(0);r.time++;r.heartbeat(true)
        assertEquals(ArmCommandStatus.TIMED_OUT,r.service.snapshot().status)
        assertEquals(1,r.intents.size)
    }
    @Test fun concurrentIntentsAreRejectedAndNoAutomaticRetryExists() {
        val r=Rig();r.request()
        assertTrue(r.request() is ArmRequestResult.Rejected)
        assertEquals(1,r.intents.size)
        r.time+=5000;r.service.tick();r.time+=5000;r.service.tick()
        assertEquals(1,r.intents.size)
    }
    @Test fun lostLinkAndExplicitCancelNeverReplayOrClaimRetraction() {
        val r=Rig();r.request();r.service.linkLost()
        assertEquals(ArmCommandStatus.ABORTED,r.service.snapshot().status)
        r.time++;r.ack();r.time++;r.heartbeat(true)
        assertEquals(ArmCommandStatus.ABORTED,r.service.snapshot().status)
        val c=Rig();c.request();c.service.cancel()
        assertEquals(ArmCommandStatus.CANCELLED,c.service.snapshot().status)
        assertTrue(c.service.snapshot().reason.contains("không thu hồi"))
        assertEquals(1,c.intents.size)
    }
    @Test fun disarmRequiresIndependentApprovalConfirmationFreshHeartbeatAndGroundEvidence() {
        val r=Rig()
        assertTrue(r.request(false,r.evidence(true).copy(onGroundVerified=false)) is ArmRequestResult.Rejected)
        val e=r.evidence(true).copy(rcInputVerified=false,preArmClear=false)
        assertTrue(r.request(false,e) is ArmRequestResult.Accepted)
        assertFalse(r.intents.single().desiredArmed)
        r.time++;r.ack();r.time++;r.heartbeat(false)
        assertEquals(ArmCommandStatus.CONFIRMED,r.service.snapshot().status)
    }
    @Test fun missingSinkAndSinkExceptionAreFailuresWithoutAckOrConfirmation() {
        val e=ArmTelemetryEvidence(42,1,10000,false,true,true,true)
        val noSink=ArmCommandService({10000},HardwareTierApproval{true})
        assertTrue(noSink.request(true,true,e) is ArmRequestResult.Rejected)
        assertEquals(ArmCommandStatus.FAILED,noSink.snapshot().status)
        assertFalse(noSink.snapshot().intentAccepted)
        val throwing=ArmCommandService({10000},HardwareTierApproval{true},ArmIntentSink{error("simulated")})
        assertTrue(throwing.request(true,true,e) is ArmRequestResult.Rejected)
        assertEquals(ArmCommandStatus.FAILED,throwing.snapshot().status)
    }
    @Test fun staleWrongSourceOrUnknownHeartbeatCannotConfirm() {
        val r=Rig();r.request();r.time++;r.ack();r.time++
        r.service.onHeartbeat(43,1,true,r.time)
        r.service.onHeartbeat(42,2,true,r.time)
        r.heartbeat(true,r.time+1)
        r.heartbeat(true,r.time-1500)
        assertFalse(r.service.snapshot().heartbeatConfirmed)
        r.heartbeat(null)
        assertEquals(ArmCommandStatus.ABORTED,r.service.snapshot().status)
    }
}
