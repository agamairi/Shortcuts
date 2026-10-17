package com.shortcuts.app.automation.android

import android.content.Intent
import com.shortcuts.app.automation.android.replay.launchFlagsForPolicy
import com.shortcuts.app.automation.model.LaunchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPolicyFlagsTest {

    @Test
    fun `RESET_TO_ROOT includes FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_CLEAR_TASK`() {
        val flags = launchFlagsForPolicy(LaunchPolicy.RESET_TO_ROOT)
        val expected = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(expected, flags)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    @Test
    fun `RESUME_EXISTING_TASK includes FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`() {
        val flags = launchFlagsForPolicy(LaunchPolicy.RESUME_EXISTING_TASK)
        val expected = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        assertEquals(expected, flags)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
    }

    @Test
    fun `EXPLICIT_EXPORTED_ACTIVITY includes FLAG_ACTIVITY_NEW_TASK`() {
        val flags = launchFlagsForPolicy(LaunchPolicy.EXPLICIT_EXPORTED_ACTIVITY)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags)
    }

    @Test
    fun `DEEP_LINK includes FLAG_ACTIVITY_NEW_TASK`() {
        val flags = launchFlagsForPolicy(LaunchPolicy.DEEP_LINK)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags)
    }
}
