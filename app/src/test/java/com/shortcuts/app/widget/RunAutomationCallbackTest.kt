package com.shortcuts.app.widget

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAutomationCallbackTest {

    @Test
    fun `explicit AutomationIdParamKey takes priority over binding lookup`() = runTest {
        var bindingLookupCalled = false
        val explicitId = 42
        val resolvedId = AutomationIdResolver.resolveAutomationId(explicitId) {
            bindingLookupCalled = true
            100
        }

        assertEquals(42, resolvedId)
        assertTrue("Binding lookup lambda should not be invoked when explicit param exists", !bindingLookupCalled)
    }

    @Test
    fun `falls back to binding lookup when explicit param is absent`() = runTest {
        var bindingLookupCalled = false
        val explicitId: Int? = null
        val resolvedId = AutomationIdResolver.resolveAutomationId(explicitId) {
            bindingLookupCalled = true
            100
        }

        assertEquals(100, resolvedId)
        assertTrue("Binding lookup lambda should be invoked when explicit param is absent", bindingLookupCalled)
    }

    @Test
    fun `returns null when explicit param is absent and binding is missing`() = runTest {
        var bindingLookupCalled = false
        val explicitId: Int? = null
        val resolvedId = AutomationIdResolver.resolveAutomationId(explicitId) {
            bindingLookupCalled = true
            null
        }

        assertNull(resolvedId)
        assertTrue("Binding lookup lambda should be invoked when explicit param is absent", bindingLookupCalled)
    }
}
