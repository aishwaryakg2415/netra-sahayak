package com.sih.netrasahayak

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app must open. A theme that does not match AppCompatActivity crashes in
 * onCreate before anything is drawn, which the pipeline tests cannot see.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @Test
    fun mainActivityStartsAndResumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
