package io.github.fotlab.fotlab.smoke

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fotlab.fotlab.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch smoke test: the packaged debug APK must reach a resumed [MainActivity] on a
 * device/emulator without dying.
 *
 * This exercises the whole start-up path of the Compose shell — `MainApplication`, the Room /
 * DataStore wiring and the JNA load of `librawler_fotlab.so` — so a regression there fails here
 * rather than in a user's hands.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun mainActivityReachesResumedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Blocks until the activity is actually RESUMED: one that dies during start-up
            // fails the call instead of leaving the test silently green.
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse("MainActivity is finishing right after launch", activity.isFinishing)
                assertFalse("MainActivity is already destroyed right after launch", activity.isDestroyed)
            }
        }
    }
}
