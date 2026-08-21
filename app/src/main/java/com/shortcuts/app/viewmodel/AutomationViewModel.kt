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

/**
 * State held while the user has been asked to confirm a deletion that would break placed widgets.
 *
 * @param automation The shortcut the user wants to delete.
 * @param affectedWidgetCount How many homescreen widget configs reference it (always > 0 when shown).
 */
data class PendingDeletion(
    val automation: Automation,
    val affectedWidgetCount: Int
)

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

    /** Non-null while a delete-confirmation dialog should be shown. */
    private val _pendingDeletion = MutableStateFlow<PendingDeletion?>(null)
    val pendingDeletion: StateFlow<PendingDeletion?> = _pendingDeletion.asStateFlow()

    fun insert(automation: Automation) {
        viewModelScope.launch {
            try {
                repository.insert(automation)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to insert automation"
            }
        }
    }

    /**
     * Deletes [automation] immediately without checking widget references.
     *
     * Preserved for API compatibility with existing callers.  New call sites should prefer
     * [requestDelete], which gates the delete behind a confirmation dialog when the shortcut
     * is referenced by one or more placed homescreen widgets.
     */
    fun delete(automation: Automation) {
        viewModelScope.launch {
            try {
                repository.delete(automation)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to delete automation"
            }
        }
    }

    /**
     * Entry point for the Dashboard delete button.
     *
     * - If no placed widget references [automation]: deletes immediately (no dialog).
     * - If one or more placed widgets reference it: sets [pendingDeletion] so the UI can
     *   show a confirmation dialog.  The actual delete does not happen until [confirmDelete]
     *   is called.
     */
    fun requestDelete(automation: Automation) {
        viewModelScope.launch {
            try {
                val count = repository.countWidgetsReferencingAutomation(automation.id)
                if (count == 0) {
                    repository.delete(automation)
                } else {
                    _pendingDeletion.value = PendingDeletion(automation, count)
                }
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to delete automation"
            }
        }
    }

    /**
     * Called when the user taps "Delete anyway" in the confirmation dialog.
     * Performs the delete and clears [pendingDeletion].
     */
    fun confirmDelete() {
        val pending = _pendingDeletion.value ?: return
        _pendingDeletion.value = null
        viewModelScope.launch {
            try {
                repository.delete(pending.automation)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to delete automation"
            }
        }
    }

    /**
     * Called when the user dismisses the confirmation dialog without confirming.
     * Clears [pendingDeletion] and deletes nothing.
     */
    fun cancelDelete() {
        _pendingDeletion.value = null
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

    /** Saves an edited shortcut over the existing row, keyed by its id. */
    fun update(automation: Automation) {
        viewModelScope.launch {
            try {
                repository.update(automation)
            } catch (e: Exception) {
                _errorState.value = e.localizedMessage ?: "Failed to update shortcut"
            }
        }
    }

    /** Loads one shortcut so the builder can open in edit mode preloaded with its steps. */
    suspend fun getAutomationById(id: Int): Automation? = repository.getAutomationById(id)

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
