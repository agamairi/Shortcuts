package com.shortcuts.app.viewmodel

import androidx.lifecycle.ViewModel
import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecorderViewModel(private val repository: AutomationRepository) : ViewModel() {

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    suspend fun saveRecordedAutomation(automation: Automation): Result<Unit> {
        _isSaving.value = true
        return try {
            repository.insert(automation)
            _errorState.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Failed to save recorded shortcut"
            _errorState.value = msg
            Result.failure(e)
        } finally {
            _isSaving.value = false
        }
    }

    fun clearError() {
        _errorState.value = null
    }
}
