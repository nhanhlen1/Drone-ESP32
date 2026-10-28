package dev.drone.rcbench.transport

import dev.drone.rcbench.control.BenchEngine
import dev.drone.rcbench.control.MonotonicClock
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SessionLoggerTest {
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
