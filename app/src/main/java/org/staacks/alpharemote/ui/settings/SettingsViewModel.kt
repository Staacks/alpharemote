package org.staacks.alpharemote.ui.settings

import android.app.Application
import android.util.Log
import android.widget.CompoundButton
import android.widget.SeekBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset
import org.staacks.alpharemote.camera.CameraStateError
import org.staacks.alpharemote.camera.CameraStateNotBonded
import org.staacks.alpharemote.camera.CameraStateReady
import org.staacks.alpharemote.service.AlphaRemoteService
import org.staacks.alpharemote.service.ServiceRunning
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private var isAssociated: Boolean = false
    private var isBonded: Boolean = false
    private var associationName: String? = null

    data class SettingsUIState (
        var cameraState: SettingsUICameraState = SettingsUICameraState.OFFLINE,
        var cameraError: String?,
        var cameraName: String?,
        var bluetoothPermissionGranted: Boolean,
        var notificationPermissionGranted: Boolean
    )

    enum class SettingsUICameraState {
        OFFLINE,
        CONNECTED,
        ERROR,
        NOT_ASSOCIATED,
        NOT_BONDED
    }

    enum class SettingsUIAction {
        PAIR,
        UNPAIR,
        REQUEST_BLUETOOTH_PERMISSION,
        REQUEST_NOTIFICATION_PERMISSION,
        ADD_CUSTOM_BUTTON,
        HELP_CONNECTION,
        HELP_CUSTOM_BUTTONS
    }

    private val _uiState = MutableStateFlow(SettingsUIState(cameraState = SettingsUICameraState.OFFLINE, cameraError = null, cameraName = null, bluetoothPermissionGranted = true, notificationPermissionGranted = true))
    val uiState: StateFlow<SettingsUIState> = _uiState.asStateFlow()

    private val _uiAction = MutableSharedFlow<SettingsUIAction>()
    val uiAction = _uiAction.asSharedFlow()

    private val settingsStore = SettingsStore(application)

    val buttonScaleSteps = listOf(0.6f, 0.7f, 0.85f, 1.0f, 1.15f, 1.3f, 1.5f)
    var buttonScaleIndex = MutableStateFlow(buttonScaleSteps.indexOf(1.0f))
    var broadcastControl = MutableStateFlow(false)

    val customButtonListFlow = MutableStateFlow<List<CameraAction>?>(null)

    private val defaultCustomButtonList = listOf(
        CameraAction(false, null, null, null, CameraActionPreset.TRIGGER_ONCE),
        CameraAction(false, 3.0f, null, null, CameraActionPreset.TRIGGER_ONCE),
        CameraAction(false, null, null, null, CameraActionPreset.RECORD),
    )

    init {
        viewModelScope.launch {
            settingsStore.getNotificationButtonSize()?.let {
                val i = buttonScaleSteps.indexOf(it)
                if (i >= 0)
                    buttonScaleIndex.emit(i)
            }
            var customButtonList = settingsStore.getCustomButtonList()
            if (customButtonList == null) {
                customButtonList = defaultCustomButtonList
                settingsStore.saveCustomButtonList(defaultCustomButtonList)
            }
            broadcastControl.emit(settingsStore.getBroadcastControl())
            customButtonListFlow.emit(customButtonList)
            customButtonListFlow.collect{
                it?.let {
                    settingsStore.saveCustomButtonList(it)
                }
            }
        }
        viewModelScope.launch {
            AlphaRemoteService.serviceState.collectLatest { state ->
                when (val camState = (state as? ServiceRunning)?.cameraState) {
                    is CameraStateError -> _uiState.emit(uiState.value.copy(cameraState = SettingsUICameraState.ERROR, cameraError = camState.description))
                    is CameraStateNotBonded -> { //Service was launched but cameraBLE detected that the device is not bonded
                        _uiState.emit(uiState.value.copy(cameraState = SettingsUICameraState.NOT_BONDED, cameraError = null, cameraName = null))
                    }
                    is CameraStateReady -> _uiState.emit(uiState.value.copy(cameraState = SettingsUICameraState.CONNECTED, cameraName = camState.name , cameraError = null))
                    else -> {
                        if (isBonded) {
                            _uiState.emit(uiState.value.copy(cameraState = SettingsUICameraState.OFFLINE, cameraError = null, cameraName = associationName))
                        } else if (isAssociated) { //Fragment detected that the camera is associated by not bonded
                            _uiState.emit(uiState.value.copy(cameraState = SettingsUICameraState.NOT_BONDED, cameraError = null, cameraName = null))
                        } else {
                            _uiState.emit(uiState.value.copy(cameraState = SettingsUICameraState.NOT_ASSOCIATED, cameraError = null, cameraName = null))
                        }
                    }
                }
            }
        }
    }

    fun updateBluetoothPermissionState(granted: Boolean) {
        viewModelScope.launch {
            settingsStore.setBluetoothGranted(granted)
            _uiState.emit(uiState.value.copy(bluetoothPermissionGranted = granted))
        }
    }

    fun updateNotificationPermissionState(granted: Boolean) {
        viewModelScope.launch {
            settingsStore.setNotificationGranted(granted)
            _uiState.emit(uiState.value.copy(notificationPermissionGranted = granted))
        }
    }

    fun updateAssociationState(address: String?, isAssociated: Boolean, isBonded: Boolean) {
        Log.d("companion", "Updated association state: $address associated=$isAssociated bonded=$isBonded")
        this.isAssociated = isAssociated
        this.isBonded = isBonded
        viewModelScope.launch {
            if (isBonded) {
                val (storedAddress, storedName) = settingsStore.getCameraId()
                associationName = if (storedAddress == address) storedName else null
            }

            val state = uiState.value.copy()
            if (state.cameraState == SettingsUICameraState.NOT_ASSOCIATED || state.cameraState == SettingsUICameraState.NOT_BONDED || state.cameraState == SettingsUICameraState.OFFLINE) {
                state.cameraState = if (isBonded) SettingsUICameraState.OFFLINE else if (isAssociated) SettingsUICameraState.NOT_BONDED else SettingsUICameraState.NOT_ASSOCIATED
            }
            if (state.cameraState != SettingsUICameraState.CONNECTED) {
                state.cameraName = associationName
            }
            state.cameraError = null
            _uiState.emit(state)
        }
    }

    fun pair() {
        viewModelScope.launch {
            _uiAction.emit(SettingsUIAction.PAIR)
        }
    }

    fun unpair() {
        viewModelScope.launch {
            _uiAction.emit(SettingsUIAction.UNPAIR)
        }
    }

    fun reportErrorState(msg: String) {
        viewModelScope.launch {
            _uiState.emit(uiState.value.copy(cameraError = msg))
        }
    }

    fun requestBluetoothPermission() {
        viewModelScope.launch {
            _uiAction.emit(SettingsUIAction.REQUEST_BLUETOOTH_PERMISSION)
        }
    }

    fun requestNotificationPermission() {
        viewModelScope.launch {
            _uiAction.emit(SettingsUIAction.REQUEST_NOTIFICATION_PERMISSION)
        }
    }

    fun addCustomButton() {
        viewModelScope.launch {
            _uiAction.emit(SettingsUIAction.ADD_CUSTOM_BUTTON)
        }
    }

    fun helpConnection() {
        viewModelScope.launch {
            _uiAction.emit(SettingsUIAction.HELP_CONNECTION)
        }
    }

    fun helpCustomButtons() {
        viewModelScope.launch {
            _uiAction.emit(SettingsUIAction.HELP_CUSTOM_BUTTONS)
        }
    }

    fun setButtonScale(seekBar: SeekBar, progressValue: Int, fromUser: Boolean) {
        if (fromUser) {
            viewModelScope.launch {
                buttonScaleIndex.emit(progressValue)
                settingsStore.setNotificationButtonSize(buttonScaleSteps[progressValue])
            }
        }
    }

    fun setBroadcastControl(button: CompoundButton, isChecked: Boolean) {
        viewModelScope.launch {
            broadcastControl.emit(isChecked)
            settingsStore.setBroadcastControl(isChecked)
        }
    }
}