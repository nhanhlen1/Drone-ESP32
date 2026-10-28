package dev.drone.controller

import androidx.lifecycle.ViewModelStore
import dev.drone.controller.control.MonotonicClock
import dev.drone.controller.mavlink.MavFrame
import dev.drone.controller.transport.BenchSession
import dev.drone.controller.transport.SessionLogger
import dev.drone.controller.ui.Mode2Input
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Drives the production engine through the ViewModel's lifecycle boundary.
 * No Android stub return defaults, mock telemetry success or physical RC sends. */
class ControllerViewModelTest {
    @get:Rule val folder=TemporaryFolder()
    private class Clock:MonotonicClock {var now=10_000L;override fun nowMs()=now}
    private inner class Rig:Closeable {
        val clock=Clock();val session=BenchSession(clock)
        var wifiClosed=false
        val model=ControllerViewModel(session,SessionLogger(folder.newFolder()),Closeable{wifiClosed=true})
        private val store=ViewModelStore().apply{put("controller",model)}
        fun ready(){
            session.engine.connected();model.focus(true)
            val payload=byteArrayOf(0,0,0,0,2,3,0,3,3)
            session.engine.onFrame(MavFrame(0,42,1,0,payload))
            model.configure(1,1100,1900,1500,true)
            model.startBench(true,true,true);model.deadman(true)
            assertTrue(model.state.value!!.engine.enabled)
        }
        override fun close(){store.clear()}
    }
    @Test fun backgroundRevokesOutstandingPermitAndClosesLogicalLink(){Rig().use{r->
        r.ready();val permit=r.session.engine.planOverride()!!
        r.model.preview(Mode2Input(.5f,-.5f,1f,.7f))
        r.model.background()
        val e=r.model.state.value!!.engine
        assertFalse(e.enabled);assertFalse(e.connected);assertFalse(e.benchConditions.complete)
        assertFalse(r.session.engine.permitValid(permit));assertNull(r.session.engine.planOverride())
        assertEquals(1100,e.previewChannels[2])
    }}
    @Test fun focusLossAndRegainDoNotRestoreControlOrThrottle(){Rig().use{r->
        r.ready();r.model.preview(Mode2Input(throttle=1f));r.model.focus(false);r.model.focus(true)
        val e=r.model.state.value!!.engine
        assertFalse(e.enabled);assertFalse(e.deadman);assertFalse(e.benchConditions.complete)
        assertEquals(1100,e.previewChannels[2]);assertNull(r.session.engine.planOverride())
    }}
    @Test fun stopInvalidatesDeadmanAndOldPermitWithoutDisconnectingTelemetry(){Rig().use{r->
        r.ready();val old=r.session.engine.planOverride()!!;r.model.stop()
        val e=r.model.state.value!!.engine
        assertTrue(e.connected);assertFalse(e.enabled);assertFalse(e.deadman)
        assertFalse(r.session.engine.permitValid(old));assertNotNull(e.heartbeatAgeMs)
    }}
    @Test fun previewChangesNeverGenerateControlAuthority(){Rig().use{r->
        r.session.engine.connected();r.model.focus(true)
        r.model.preview(Mode2Input(1f,1f,1f,1f))
        assertFalse(r.model.state.value!!.engine.enabled)
        assertEquals(0L,r.model.state.value!!.engine.generatedOverrides)
        assertNull(r.session.engine.planOverride())
    }}
    @Test fun missingPhysicalConditionCannotBeConvertedToBenchPermit(){Rig().use{r->
        r.session.engine.connected();r.model.focus(true)
        r.session.engine.onFrame(MavFrame(0,42,1,0,byteArrayOf(0,0,0,0,2,3,0,3,3)))
        r.model.configure(1,1100,1900,1500,true)
        r.model.startBench(true,false,true);r.model.deadman(true)
        assertFalse(r.model.state.value!!.engine.enabled);assertNull(r.session.engine.planOverride())
    }}

    @Test fun criticalRcDiagnosticRequiresFourthConsentThroughViewModel(){Rig().use{r->
        r.session.engine.connected();r.model.focus(true)
        r.session.engine.onFrame(MavFrame(0,42,1,0,byteArrayOf(0,0,0,0,2,3,0,5,3)))
        val status=ByteBuffer.allocate(43).order(ByteOrder.LITTLE_ENDIAN).apply{
            putInt(0,0x10010000);putInt(4,0x10010000);putInt(8,0)
        }.array()
        r.session.engine.onFrame(MavFrame(1,42,1,1,status))
        r.model.configure(1,1100,1900,1500,true)
        r.model.startBench(true,true,true)
        assertFalse(r.model.state.value!!.engine.enabled)
        assertFalse(r.model.state.value!!.engine.benchConditions.criticalRcDiagnosticAuthorised)
        r.model.startBench(true,true,true,true);r.model.deadman(true)
        assertTrue(r.model.state.value!!.engine.enabled)
        assertTrue(r.model.state.value!!.engine.criticalRcDiagnosticActive)
        val permit=r.session.engine.planOverride()!!
        assertArrayEquals(intArrayOf(1500,65535,65535,65535),permit.channels)
        r.model.background()
        assertFalse(r.model.state.value!!.engine.benchConditions.criticalRcDiagnosticAuthorised)
        assertFalse(r.session.engine.permitValid(permit))
    }}
    @Test fun clearingViewModelStoreDisposesRuntimeAndCannotReconnect(){
        val r=Rig();r.ready();r.close();assertTrue(r.wifiClosed)
        assertFalse(r.session.snapshot().engine.connected)
        r.model.connect("127.0.0.1",5760)
        assertFalse(r.model.state.value!!.engine.connected)
        assertNull(r.session.engine.planOverride())
    }
}
