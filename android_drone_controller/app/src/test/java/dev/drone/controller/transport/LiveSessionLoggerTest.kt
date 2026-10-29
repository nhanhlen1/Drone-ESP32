package dev.drone.controller.transport

import dev.drone.controller.command.ArmCommandIntent
import dev.drone.controller.command.ArmCommandSnapshot
import dev.drone.controller.command.ArmCommandStatus
import dev.drone.controller.control.MonotonicClock
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LiveSessionLoggerTest {
    @Test fun preservesFourChannelAndCommandEvidenceWhenLegacyBenchIsStopped() {
        val folder=Files.createTempDirectory("live-session-csv").toFile()
        val session=BenchSession(MonotonicClock{123L})
        session.selectControlMode(ControlProfile.CONTROL)
        val snapshot=session.snapshot()
        assertFalse(snapshot.engine.enabled)
        val live=snapshot.live!!.copy(lastRequested=listOf(1540,1460,1100,1530),
            lastSent=listOf(1540,1460,1100,1530),lastEcho=listOf(1540,1460,1100,1530),rcWriteCount=17,
            armCommand=ArmCommandSnapshot(ArmCommandStatus.ACK_ACCEPTED_WAITING_HEARTBEAT,
                ArmCommandIntent(7,42,1,true),"ACK chấp nhận; chờ HEARTBEAT",intentAccepted=true,
                ackResult=0,socketWritten=true,heartbeatConfirmed=false))
        val logger=SessionLogger(folder,wallClockMs={777L})
        logger.record(snapshot.copy(live=live),123L)
        logger.close()
        val deadline=System.nanoTime()+2_000_000_000L
        while(System.nanoTime()<deadline && (!logger.file.isFile || logger.file.readText().lines().size<3))Thread.sleep(10)
        val lines=logger.file.readLines()
        val columns=lines[0].split(',')
        // Logger quotes every cell; preserve commas and doubled quote escapes.
        val values=Regex("\"((?:[^\"]|\"\")*)\"(?:,|$)").findAll(lines[1])
            .map{it.groupValues[1].replace("\"\"","\"")}.toList()
        fun cell(name:String)=values[columns.indexOf(name)]
        assertEquals(columns.size,values.size)
        assertEquals("false",cell("bench_enabled"))
        assertEquals("CONTROL",cell("control_profile"))
        assertEquals("1540;1460;1100;1530",cell("control_tx_channels"))
        assertEquals("1540;1460;1100;1530",cell("control_echo_channels"))
        assertEquals("true",cell("arm_socket_written"))
        assertEquals("0",cell("arm_ack_result"))
        assertEquals("false",cell("arm_heartbeat_confirmed"))
        assertEquals("17",cell("control_write_count"))
        assertNull(logger.error)
        session.close();folder.deleteRecursively()
    }
}
