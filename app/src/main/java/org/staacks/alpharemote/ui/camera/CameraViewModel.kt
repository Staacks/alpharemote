package org.staacks.alpharemote.ui.camera

import android.app.Application
import android.text.Editable
import android.view.MotionEvent
import android.view.View
import androidx.databinding.Observable
import androidx.databinding.ObservableField
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.CameraStateReady
import org.staacks.alpharemote.service.AlphaRemoteService
import org.staacks.alpharemote.service.ServiceRunning


class CameraViewModel(application: Application) : AndroidViewModel(application) {

    data class CameraUIState (
        var connected: Boolean = false,
        var serviceState: ServiceRunning? = null,
        var cameraState: CameraStateReady? = null,
    )

    var bulbToggle: ObservableField<Boolean> = ObservableField(false)
    var bulbDuration: ObservableField<Double?> = ObservableField(5.0)
    var intervalToggle: ObservableField<Boolean> = ObservableField(false)
    var intervalCount: ObservableField<Int?> = ObservableField(50)
    var intervalDuration: ObservableField<Double?> = ObservableField(3.0)
    var focusBracketingToggle: ObservableField<Boolean> = ObservableField(false)
    var focusBracketingAmount: ObservableField<Double?> = ObservableField(2.0)

    fun storeAdvancedControlsState() {
        viewModelScope.launch {
            settingsStore.setAdvancedControlsState(
                SettingsStore.AdvancedControlsStateOptional(
                    bulbToggle.get(),
                    bulbDuration.get(),
                    intervalToggle.get(),
                    intervalCount.get(),
                    intervalDuration.get(),
                    focusBracketingToggle.get(),
                    focusBracketingAmount.get()
                )
            )
        }
    }

    fun onAdvancedControlsTextChanged(s: Editable) {
        storeAdvancedControlsState()
    }

    val advancedControlsChangedCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            storeAdvancedControlsState()
        }
    }

    sealed class CameraUIAction

    data class GenericCameraUIAction (
        val action: GenericCameraUIActionType
    ) : CameraUIAction()

    enum class GenericCameraUIActionType {
        GOTO_DEVICE_SETTINGS,
        HELP_REMOTE,
        START_ADVANCED_SEQUENCE
    }

    data class DefaultRemoteButtonCameraUIAction (
        val event: Int,
        val button: DefaultRemoteButton.Button
    ) : CameraUIAction()

    private val _uiState = MutableStateFlow(CameraUIState())
    val uiState: StateFlow<CameraUIState> = _uiState.asStateFlow()

    private val _uiAction = MutableSharedFlow<CameraUIAction>()
    val uiAction = _uiAction.asSharedFlow()

    private val settingsStore = SettingsStore(application)


    init {
        viewModelScope.launch {
            settingsStore.getAdvancedControlsState().let { saved ->
                bulbToggle.set(saved.bulbEnabled)
                bulbDuration.set(saved.bulbDuration)
                intervalCount.set(saved.intervalCount)
                intervalToggle.set(saved.intervalEnabled)
                intervalDuration.set(saved.intervalDuration)
                focusBracketingToggle.set(saved.focusBracketingEnabled)
                focusBracketingAmount.set(saved.focusBracketingAmount)
            }
            bulbToggle.addOnPropertyChangedCallback(advancedControlsChangedCallback)
            intervalToggle.addOnPropertyChangedCallback(advancedControlsChangedCallback)
            focusBracketingToggle.addOnPropertyChangedCallback(advancedControlsChangedCallback)
            AlphaRemoteService.serviceState.collectLatest {
                (it as? ServiceRunning)?.also { serviceRunning ->
                    _uiState.value = uiState.value.copy(
                        serviceState = serviceRunning,
                        cameraState = (serviceRunning.cameraState as? CameraStateReady),
                        connected = (serviceRunning.cameraState is CameraStateReady)
                    )
                } ?: run {
                    _uiState.value = uiState.value.copy(serviceState = null, cameraState = null, connected = false)
                }
            }
        }
    }

    fun gotoDeviceSettings() {
        viewModelScope.launch {
            _uiAction.emit(GenericCameraUIAction(GenericCameraUIActionType.GOTO_DEVICE_SETTINGS))
        }
    }

    fun helpRemote() {
        viewModelScope.launch {
            _uiAction.emit(GenericCameraUIAction(GenericCameraUIActionType.HELP_REMOTE))
        }
    }

    fun defaultRemoteButtonOnTouchListener(view: View, event: MotionEvent): Boolean {
        if (event.action in arrayOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
            (view as? DefaultRemoteButton)?.let {
                viewModelScope.launch {
                    _uiAction.emit(DefaultRemoteButtonCameraUIAction(event.action, it.button))
                }
            }

            //Set pressed state to show ripple effect
            view.isPressed = event.action == MotionEvent.ACTION_DOWN
        }

        return true
    }

    fun startAdvancedSequence() {
        viewModelScope.launch {
            _uiAction.emit(GenericCameraUIAction(GenericCameraUIActionType.START_ADVANCED_SEQUENCE))
        }
    }
}