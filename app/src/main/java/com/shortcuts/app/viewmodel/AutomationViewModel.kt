package com.shortcuts.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey

class AutomationViewModel(private val repository: AutomationRepository) : ViewModel() {

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    val uiState: StateFlow<UiState<List<Automation>>> = repository.allAutomations
        .map<List<Automation>, UiState<List<Automation>>> { automations ->
            UiState.Success(automations)
        }
        .catch { e ->
            emit(UiState.Error(e.localizedMessage ?: "Failed to fetch automations", e))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UiState.Loading
        )

    fun insert(automation: Automation) {
        viewModelScope.launch {
            try {
                repository.insert(automation)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to insert automation"
            }
        }
    }

    fun delete(automation: Automation) {
        viewModelScope.launch {
            try {
                repository.delete(automation)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to delete automation"
            }
        }
    }

    fun toggleActive(automation: Automation) {
        viewModelScope.launch {
            try {
                repository.toggleActive(automation)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to toggle active state"
            }
        }
    }

    fun updateAppearance(automation: Automation, colorKey: WidgetColorKey, iconKey: WidgetIconKey) {
        viewModelScope.launch {
            try {
                val updated = automation.copy(colorKey = colorKey.name, iconKey = iconKey.name)
                repository.update(updated)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to update automation appearance"
            }
        }
    }

    fun clearError() {
        _errorState.value = null
    }
}
