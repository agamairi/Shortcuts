package com.shortcuts.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutomationAccessibilityStressTest {

    private lateinit var service: AutomationAccessibilityService
    private lateinit var mockRootNode: AccessibilityNodeInfo

    @Before
    fun setup() {
        service = spyk(AutomationAccessibilityService())
        mockRootNode = mockk(relaxed = true)
        every { service.getRootNode() } returns mockRootNode
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    /**
     * EMPIRICAL TEST 1: Null Node Info & Null Children Handling
     */
    @Test
    fun testNullRootNodeAndNullChildrenHandling() {
        // 1. Root node is null
        every { service.getRootNode() } returns null
        val action1 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "btn_1")
        assertFalse(service.executeAction(action1))

        // 2. Root node exists but getChild returns null for indices
        every { service.getRootNode() } returns mockRootNode
        every { mockRootNode.childCount } returns 3
        every { mockRootNode.getChild(any()) } returns null
        every { mockRootNode.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { mockRootNode.findAccessibilityNodeInfosByText(any()) } returns emptyList()

        val action2 = Action(actionType = ActionType.UI_AUTOMATION, targetText = "NonExistent")
        val foundNode = service.findTargetNode(action2)
        assertNull(foundNode)

        // 3. Action with null fields
        val actionNulls = Action(actionType = ActionType.UI_AUTOMATION)
        assertFalse(service.executeAction(actionNulls))
    }

    /**
     * EMPIRICAL TEST 2: Invalid Action Types & Strict Global Action Rejection
     */
    @Test
    fun testInvalidGlobalActionRejection() {
        // Global action with unknown key string "UNKNOWN_GLOBAL_KEY"
        val invalidGlobalAction = Action(
            actionType = ActionType.UI_AUTOMATION,
            globalAction = "UNKNOWN_GLOBAL_KEY"
        )

        // Strict global navigation validation returns false without calling performGlobalAction
        val result = service.executeAction(invalidGlobalAction)
        assertFalse(result)
        verify(exactly = 0) { service.performGlobalAction(any()) }

        // Action with empty string targets
        val emptyAction = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "",
            targetNodeId = "",
            targetText = ""
        )
        assertFalse(service.executeAction(emptyAction))
    }

    /**
     * EMPIRICAL TEST 3: Deep & Cyclic Child Tree Recursion Protection
     */
    @Test
    fun testDeepChildTreeRecursionProtection() {
        every { mockRootNode.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { mockRootNode.findAccessibilityNodeInfosByText(any()) } returns emptyList()

        // Create a chain of 50 child nodes to simulate a deep tree
        val nodeChain = List(50) { mockk<AccessibilityNodeInfo>(relaxed = true) }
        every { mockRootNode.childCount } returns 1
        every { mockRootNode.getChild(0) } returns nodeChain[0]
        for (i in 0 until 49) {
            every { nodeChain[i].childCount } returns 1
            every { nodeChain[i].getChild(0) } returns nodeChain[i + 1]
        }
        every { nodeChain[49].childCount } returns 0

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            targetText = "NonExistent"
        )
        // Must complete without StackOverflowError and return null
        val result = service.findTargetNode(action)
        assertNull(result)
    }

    @Test
    fun testCyclicChildTreeRecursionProtection() {
        every { mockRootNode.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { mockRootNode.findAccessibilityNodeInfosByText(any()) } returns emptyList()

        val cyclicChild = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockRootNode.childCount } returns 1
        every { mockRootNode.getChild(0) } returns cyclicChild
        every { cyclicChild.childCount } returns 1
        every { cyclicChild.getChild(0) } returns mockRootNode // Cycle back to root

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            targetText = "NonExistent"
        )
        val result = service.findTargetNode(action)
        assertNull(result)
    }

    /**
     * EMPIRICAL TEST 4: Concurrent Action Execution Multi-threading Safety
     */
    @Test
    fun testConcurrentActionExecution() {
        val executor = Executors.newFixedThreadPool(10)
        val threadCount = 20
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val clickableNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { clickableNode.isClickable } returns true
        every { clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        every { mockRootNode.findAccessibilityNodeInfosByViewId("id/concurrent_btn") } returns listOf(clickableNode)

        val context = mockk<Context>(relaxed = true)
        val actionExecutor = ActionExecutorService(context, service)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "id/concurrent_btn"
        )

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    val res = actionExecutor.executeAction(action)
                    if (res is StepResult.Success) successCount.incrementAndGet()
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                    t.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent threads should complete within timeout", completed)
        assertEquals(0, errorCount.get())
        assertEquals(threadCount, successCount.get())
    }
}
