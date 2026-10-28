package dev.drone.controller

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires emulator or physical Android; compilation does not count as execution. */
@RunWith(AndroidJUnit4::class)
class ControllerActivityTest {
    @Test fun activityRecreationDoesNotConnectOrGrantRc(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.recreate()
            scenario.onActivity{activity->
                val model=androidx.lifecycle.ViewModelProvider(activity)[ControllerViewModel::class.java]
                val engine=model.state.value!!.engine
                assertFalse(engine.connected);assertFalse(engine.enabled)
                assertFalse(engine.benchConditions.complete)
                assertEquals(1100,engine.previewChannels[2])
            }
        }
    }
    @Test fun stoppedActivityHasNoControlAuthority(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity{activity->
                val engine=androidx.lifecycle.ViewModelProvider(activity)[ControllerViewModel::class.java].state.value!!.engine
                assertFalse(engine.connected);assertFalse(engine.enabled)
            }
        }
    }
    @Test fun manifestHasOnlyNetworkPermissions(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS)
        val requested=info.requestedPermissions?.toSet().orEmpty()
        assertTrue(requested.contains(android.Manifest.permission.INTERNET))
        assertTrue(requested.contains(android.Manifest.permission.ACCESS_NETWORK_STATE))
        assertFalse(requested.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(requested.contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
}
