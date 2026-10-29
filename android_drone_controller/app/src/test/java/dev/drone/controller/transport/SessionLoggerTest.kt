package dev.drone.controller.transport

import dev.drone.controller.control.BenchEngine
import dev.drone.controller.control.MonotonicClock
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SessionLoggerTest {
    @Test fun boundedDiskUsageReportsDroppedRowsAndKeepsUnrelatedFiles() {
        val folder=Files.createTempDirectory("bounded-rc-log").toFile()
        val note=java.io.File(folder,"operator-notes.txt").apply{writeText("keep")}
        repeat(4){java.io.File(folder,"rcbench-123-$it.csv").writeText("old")}
        val logger=SessionLogger(folder,maximumFileBytes=2048,retainedFiles=2,wallClockMs={777})
        val snapshot=SessionSnapshot(BenchEngine(MonotonicClock{123L}).snapshot(),"TEST",null,null,0,0,0,0.0,0,0)
        repeat(10){logger.record(snapshot,123)}
        val deadline=System.nanoTime()+2_000_000_000
        while(System.nanoTime()<deadline&&!logger.sizeLimitReached)Thread.sleep(10)
        logger.close()
        assertTrue(logger.sizeLimitReached)
        assertTrue(logger.dropped.get()>0)
        assertNotNull(logger.error)
        assertTrue(logger.file.length()<=2048)
        assertEquals("keep",note.readText())
        assertTrue(folder.listFiles()!!.count{it.extension=="csv"}<=2)
        val csv=logger.file.readText()
        assertTrue(csv.contains("time_wall_ms"))
        assertTrue(csv.contains("\"777\""))
    }
    @Test fun asynchronousPrivateCsvContainsActualStateAndCloses() {
        val folder=Files.createTempDirectory("rc-log").toFile()
        val logger=SessionLogger(folder)
        val snapshot=SessionSnapshot(BenchEngine(MonotonicClock{123L}).snapshot(),"TEST",null,null,0,0,0,0.0,0,0)
        logger.record(snapshot,123);logger.close()
        val deadline=System.nanoTime()+2_000_000_000
        while(System.nanoTime()<deadline&&(!logger.file.exists()||logger.file.readText().lines().size<3))Thread.sleep(10)
        val text=logger.file.readText()
        assertTrue(text.contains("time_monotonic_ms"));assertTrue(text.contains("\"123\""));assertTrue(text.contains("UNKNOWN"))
        assertNull(logger.error)
        folder.deleteRecursively()
    }
    @Test fun storageFailureIsReportedWithoutCrashingControl() {
        val notDirectory=Files.createTempFile("bad-log", ".tmp").toFile()
        val logger=SessionLogger(notDirectory)
        val deadline=System.nanoTime()+2_000_000_000
        while(System.nanoTime()<deadline&&logger.error==null)Thread.sleep(10)
        assertNotNull(logger.error);logger.close();notDirectory.delete()
    }
}
