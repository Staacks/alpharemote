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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.staacks.alpharemote.MainActivity
import org.staacks.alpharemote.camera.CameraStateRemoteDisabled

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private var isAssociated: Boolean = false
    private var isBonded: Boolean = false
    private var associationName: String? = null

    private var bluetoothEnabled: Boolean = false
    private var locationServiceEnabled: Boolean = false
    private var bleScanningEnabled: Boolean = false

    data class SettingsUIState (
        var cameraState: SettingsUICameraState = SettingsUICameraState.OFFLINE,
        var cameraError: String?,
        var cameraName: String?,
        var bluetoothPermissionGranted: Boolean,
        var notificationPermissionGranted: Boolean,
        var bluetoothEnabled: Boolean,
        var locationServiceEnabled: Boolean,
        var bleScanningEnabled: Boolean
    )

    enum class SettingsUICameraState {
        OFFLINE,
        CONNECTED,
        ERROR,
        NOT_ASSOCIATED,
        NOT_BONDED,
        REMOTE_DISABLED
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

    private val _uiState = MutableStateFlow(SettingsUIState(cameraState = SettingsUICameraState.OFFLINE, cameraError = null, cameraName = null, bluetoothPermissionGranted = true, notificationPermissionGranted = true, bluetoothEnabled = false, locationServiceEnabled = false, bleScanningEnabled = false))
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
                    buttonScaleIndex.value = i
            }

            var customButtonList = settingsStore.getCustomButtonList()
            if (customButtonList == null) {
                customButtonList = defaultCustomButtonList
                settingsStore.saveCustomButtonList(defaultCustomButtonList)
            }
            customButtonListFlow.value = customButtonList

            broadcastControl.value = settingsStore.getEnableExternalBroadcastControl()

            customButtonListFlow.collect{
                it?.let {
                    settingsStore.saveCustomButtonList(it)
                }
            }
        }
        viewModelScope.launch {
            AlphaRemoteService.serviceState.collectLatest { state ->
                when (val camState = (state as? ServiceRunning)?.cameraState) {
                    is CameraStateError -> _uiState.update { it.copy(cameraState = SettingsUICameraState.ERROR, cameraError = camState.description) }
                    is CameraStateNotBonded -> { //Service was launched but cameraBLE detected that the device is not bonded
                        _uiState.update{it.copy(cameraState = SettingsUICameraState.NOT_BONDED, cameraError = null, cameraName = null)}
                    }
                    is CameraStateRemoteDisabled -> { //Service was launched but cameraBLE suspects that the remote function is disabled
                        _uiState.update { it.copy(cameraState = SettingsUICameraState.REMOTE_DISABLED, cameraError = null, cameraName = null)}
                    }
                    is CameraStateReady -> _uiState.update { it.copy(cameraState = SettingsUICameraState.CONNECTED, cameraName = camState.name , cameraError = null)}
                    else -> {
                        if (isBonded) {
                            _uiState.update{it.copy(cameraState = SettingsUICameraState.OFFLINE, cameraError = null, cameraName = associationName)}
                        } else if (isAssociated) { //Fragment detected that the camera is associated by not bonded
                            _uiState.update { uiState.value.copy(cameraState = SettingsUICameraState.NOT_BONDED, cameraError = null, cameraName = null)}
                        } else {
                            _uiState.update { uiState.value.copy(cameraState = SettingsUICameraState.NOT_ASSOCIATED, cameraError = null, cameraName = null)}
                        }
                    }
                }
            }
        }
    }

    fun updateBluetoothPermissionState(granted: Boolean) {
        viewModelScope.launch {
            settingsStore.setBluetoothGranted(granted)
            _uiState.update { it.copy(bluetoothPermissionGranted = granted)}
        }
    }

    fun updateNotificationPermissionState(granted: Boolean) {
        viewModelScope.launch {
            settingsStore.setNotificationGranted(granted)
            _uiState.update {it.copy(notificationPermissionGranted = granted)}
        }
    }

    fun updateAssociationState(address: String?, isAssociated: Boolean, isBonded: Boolean) {
        Log.d(MainActivity.TAG, "Updated association state: $address associated=$isAssociated bonded=$isBonded")
        this.isAssociated = isAssociated
        this.isBonded = isBonded
        viewModelScope.launch {
            if (isBonded) {
                val (storedAddress, storedName) = settingsStore.getCameraId()
                associationName = if (storedAddress == address) storedName else null
            }

            _uiState.update {
                val state = uiState.value.copy()
                if (state.cameraState == SettingsUICameraState.NOT_ASSOCIATED || state.cameraState == SettingsUICameraState.NOT_BONDED || state.cameraState == SettingsUICameraState.OFFLINE) {
                    state.cameraState = if (isBonded) SettingsUICameraState.OFFLINE else if (isAssociated) SettingsUICameraState.NOT_BONDED else SettingsUICameraState.NOT_ASSOCIATED
                }
                if (state.cameraState != SettingsUICameraState.CONNECTED) {
                    state.cameraName = associationName
                }
                state.cameraError = null
                state
            }
        }
    }

    fun updateBluetoothState(enabled: Boolean) {
        bluetoothEnabled = enabled
        _uiState.update{it.copy(bluetoothEnabled = enabled)}
    }

    fun updateLocationServiceState(locationServiceEnabled: Boolean, bleScanningEnabled: Boolean) {
        this.locationServiceEnabled = locationServiceEnabled
        this.bleScanningEnabled = bleScanningEnabled
        _uiState.update { it.copy(locationServiceEnabled = locationServiceEnabled, bleScanningEnabled = bleScanningEnabled) }
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
        _uiState.update { it.copy(cameraError = msg) }
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
                buttonScaleIndex.value = progressValue
                settingsStore.setNotificationButtonSize(buttonScaleSteps[progressValue])
            }
        }
    }

    fun incrementButtonScale() {
        val newIndex = buttonScaleIndex.value + 1
        if (newIndex < buttonScaleSteps.count()) {
            viewModelScope.launch {
                buttonScaleIndex.value = newIndex
                settingsStore.setNotificationButtonSize(buttonScaleSteps[newIndex])
            }
        }
    }

    fun decrementButtonScale() {
        val newIndex = buttonScaleIndex.value - 1
        if (newIndex >= 0) {
            viewModelScope.launch {
                buttonScaleIndex.value = newIndex
                settingsStore.setNotificationButtonSize(buttonScaleSteps[newIndex])
            }
        }
    }

    fun setBroadcastControl(button: CompoundButton, isChecked: Boolean) {
        viewModelScope.launch {
            broadcastControl.value = isChecked
            settingsStore.setEnableExternalBroadcastControl(isChecked)
        }
    }
}