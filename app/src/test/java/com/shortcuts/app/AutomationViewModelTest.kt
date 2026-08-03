package com.shortcuts.app

import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.viewmodel.AutomationViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit val repository: AutomationRepository
    private lateinit val viewModel: AutomationViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        
        // Mock the flow
        coEvery { repository.allAutomations } returns flowOf(emptyList())
        viewModel = AutomationViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `insert automation calls repository insert`() = runTest {
        val automation = Automation(id = 1, name = "Test", actionsJson = "[]")
        
        coEvery { repository.insert(automation) } returns Unit
        
        viewModel.insert(automation)
        
        // Advance dispatcher to execute coroutine
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify(exactly = 1) { repository.insert(automation) }
    }
}
