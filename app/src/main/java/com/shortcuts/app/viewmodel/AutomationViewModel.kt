package com.shortcuts.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationViewModel(private val repository: AutomationRepository) : ViewModel() {

    val allAutomations: StateFlow<List<Automation>> = repository.allAutomations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun insert(automation: Automation) {
        viewModelScope.launch {
            repository.insert(automation)
        }
    }
}
