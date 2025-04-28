package org.staacks.alpharemote.service

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.staacks.alpharemote.MainActivity
import org.staacks.alpharemote.R
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.ButtonCode
import org.staacks.alpharemote.camera.CAButton
import org.staacks.alpharemote.camera.CACountdown
import org.staacks.alpharemote.camera.CAJog
import org.staacks.alpharemote.camera.CAWaitFor
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset
import org.staacks.alpharemote.camera.CameraActionStep
import org.staacks.alpharemote.camera.CameraBLE
import org.staacks.alpharemote.camera.CameraStateIdentified
import org.staacks.alpharemote.camera.CameraStateReady
import org.staacks.alpharemote.camera.WaitTarget
import java.util.LinkedList
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule
import kotlin.math.roundToLong


class AlphaRemoteService : CompanionDeviceService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var timer: TimerTask? = null
    private var notificationUI: NotificationUI? = null

    private var locationManager: LocationManager? = null

    companion object {

        private var cameraBLE: CameraBLE? = null
        private var deviceAppearedCount = 0

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceStateGone())
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        fun disconnect() {
            cameraBLE?.disconnectFromDevice()
        }

        const val BUTTON_INTENT_ACTION = "NOTIFICATION_BUTTON"
        const val BUTTON_INTENT_CAMERA_ACTION_EXTRA = "camera_action"
        const val BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA = "down"
        const val BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA = "up"

        const val BULB_INTENT_ACTION = "BULB"
        const val BULB_INTENT_DURATION_EXTRA = "duration"

        const val INTERVAL_INTENT_ACTION = "INTERVAL"
        const val INTERVAL_INTENT_DURATION_EXTRA = "interval"
        const val INTERVAL_INTENT_COUNT_EXTRA = "count"

        private var pendingActionSteps = LinkedList<CameraActionStep>()
        var broadcastControl = false
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        Log.d(MainActivity.TAG, "Device appeared: $address")
        try {
            super.onDeviceAppeared(address) //This is abstract on Android 12
        } catch (_: AbstractMethodError) {}

        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(MainActivity.TAG, "Missing Bluetooth permission. Launching activity instead.")
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.NAVIGATE_TO_INTENT_EXTRA, R.id.navigation_settings)
            }
            startActivity(intent)
            stopSelf()
            return
        }

        val settingsStore = SettingsStore(application)
        notificationUI = notificationUI ?: (NotificationUI(applicationContext).also { notificationUI ->
            scope.launch {
                settingsStore.customButtonSettings.stateIn(
                    scope = this,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = SettingsStore.CustomButtonSettings(null, 1.0f)
                ).collectLatest {
                    notificationUI.updateCustomButtons(it.customButtonList, it.scale)
                }
            }
            scope.launch {
                settingsStore.permissions.collectLatest {
                    if (it.notification) //Refresh notification if notification permission has been granted after it was not granted previously
                        notificationUI.updateNotification()
                    broadcastControl = it.broadcastControl
                }
            }
        })

        if (cameraBLE == null) {
            cancelPendingActionSteps()
            deviceAppearedCount = 1
            cameraBLE = CameraBLE(scope, application, address, ::onConnect, ::onDisconnect).apply {
                scope.launch {
                    cameraState.collect {
                        when (it) {
                            is CameraStateReady -> checkWaitAction(it)
                            is CameraStateIdentified -> settingsStore.setCameraId(it.name, it.address)
                            else -> cancelPendingActionSteps()
                        }
                    }
                }
                scope.launch {
                    cameraState.collectLatest { cameraState ->
                        _serviceState.update {
                            (it as? ServiceRunning)?.copy(cameraState = cameraState) ?: ServiceRunning(cameraState, null, null)
                        }
                        notificationUI?.onCameraStateUpdate(cameraState)
                    }
                }
            }
        } else {
            Log.w(MainActivity.TAG, "onDeviceAppeared ignored as cameraBLE has already been instantiated.")
            deviceAppearedCount++
            return
        }
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        Log.d(MainActivity.TAG, "API33 onDeviceAppeared: $associationInfo")
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        Log.d(MainActivity.TAG, "Device disappeared: $address")
        try {
            super.onDeviceDisappeared(address) //This is abstract on Android 12
        } catch (_: AbstractMethodError) {}
        deviceAppearedCount--

        if (deviceAppearedCount == 0) {
            cameraBLE?.disconnectFromDevice()
            cameraBLE = null
            job.cancelChildren()
            stopSelf()
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)
        Log.d(MainActivity.TAG, "API33 onDeviceDisappeared: $associationInfo")
    }

    private fun onConnect() {
        Log.d(MainActivity.TAG, "onConnect")
        notificationUI?.let {
            startForeground(
                it.notificationId,
                it.start(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        }
        startLocationUpdates()
    }

    private fun onDisconnect() {
        Log.d(MainActivity.TAG, "onDisconnect")
        stopLocationUpdates()
        _serviceState.value = ServiceStateGone()
        cancelPendingActionSteps()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationUI?.stop()
    }

    private fun executeCameraAction(cameraAction: CameraAction, down: Boolean, up: Boolean) {
        var translatedUp = up
        var translatedDown = down

        // Translate toggle release to down or up depending on button state
        if (cameraAction.toggle) {
            translatedDown = false // Toggle only acts on button release. Do not pass through down events
            if (translatedUp) {
                ((serviceState.value as? ServiceRunning)?.cameraState as? CameraStateReady)?.let { cameraState ->
                    translatedUp = cameraAction.preset.template.referenceButton in cameraState.pressedButtons
                    translatedDown = !translatedUp
                }
            }
        }

        if (translatedDown && translatedUp) //Simple click, i.e. button in notification area
            startCameraAction(cameraAction.getClickStepList(this))
        else if (translatedDown) //Button released
            startCameraAction(cameraAction.getPressStepList(this))
        else if (translatedUp) //Button pressed
            startCameraAction(cameraAction.getReleaseStepList())
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(MainActivity.TAG, "onStartCommand: $intent")
        when (intent?.action) {
            BUTTON_INTENT_ACTION -> {
                val cameraAction = intent.getSerializableExtra(BUTTON_INTENT_CAMERA_ACTION_EXTRA) as CameraAction
                val down = intent.getBooleanExtra(BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA, true)
                val up = intent.getBooleanExtra(BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA, true)

                executeCameraAction(cameraAction, down, up)
            }
            BULB_INTENT_ACTION -> {
                val duration = intent.getSerializableExtra(BULB_INTENT_DURATION_EXTRA) as Float
                startCameraAction(
                    CameraAction(false,null,null,null,CameraActionPreset.TRIGGER_ONCE).getClickStepList(this)
                            + CACountdown(getString(R.string.camera_advanced_bulb_timer_label), duration)
                            + CameraAction(false,null,null,null, CameraActionPreset.SHUTTER).getClickStepList(this)
                )
            }
            INTERVAL_INTENT_ACTION -> {
                val duration = intent.getSerializableExtra(INTERVAL_INTENT_DURATION_EXTRA) as Float
                val count = intent.getSerializableExtra(INTERVAL_INTENT_COUNT_EXTRA) as Int
                startCameraAction(
                    List(count) {
                        listOf(
                            CAButton(pressed = true, ButtonCode.SHUTTER_HALF),
                            CACountdown(getString(R.string.camera_advanced_interval_timer_label), duration),
                            CAButton(pressed = true, ButtonCode.SHUTTER_FULL),
                            CAButton(pressed = false, ButtonCode.SHUTTER_FULL),
                            CAButton(pressed = false, ButtonCode.SHUTTER_HALF)
                        )
                    }.flatten()
                )
            }
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch {
            SettingsStore(application).getCustomButtonList().let { customButtonList ->
                SettingsStore(application).getNotificationButtonSize()?.let { notificationButtonSize ->
                    notificationUI?.updateCustomButtons(customButtonList, notificationButtonSize)
                }
            }
        }
    }

    @Synchronized
    fun cancelPendingActionSteps(): Boolean {
        var pendingStepsCancelled = false
        timer?.cancel()
        if (pendingActionSteps.isNotEmpty()) {
            pendingActionSteps.clear()
            for (button in ButtonCode.entries) {
                cameraBLE?.executeCameraActionStep(CAButton(false, button))
            }
            pendingStepsCancelled = true
        }
        _serviceState.update {
            (it as? ServiceRunning)?.copy(countdown = null, countdownLabel = null) ?: it
        }
        notificationUI?.hideCountdown()
        return pendingStepsCancelled
    }

    private fun isLongRunningSequence(steps: List<CameraActionStep>): Boolean {
        for (step in steps) {
            when (step) {
                is CACountdown -> return true
                is CAWaitFor -> return true
                else -> {}
            }
        }
        return false
    }

    @Synchronized
    fun startCameraAction(steps: List<CameraActionStep>) {
        if (cancelPendingActionSteps() && isLongRunningSequence(steps))
            return //If this is more than a simple button press and there were pending action, this button press is only used as a cancellation of the previous sequence
        pendingActionSteps.addAll(steps)
        executeNextCameraActionStep()
    }

    @Synchronized
    fun executeNextCameraActionStep() {
        while ((pendingActionSteps.peek() is CAButton || pendingActionSteps.peek() is CAJog)) {
            pendingActionSteps.poll()?.let {
                cameraBLE?.executeCameraActionStep(it)
            }
        }
        (pendingActionSteps.peek() as? CACountdown)?.let { step ->
            val time = (step.duration * 1000).roundToLong()
            timer?.cancel()
            timer = Timer().schedule(time) {
                countdownActionComplete()
            }
            val targetTime = SystemClock.elapsedRealtime() + time
            _serviceState.update {
                (it as? ServiceRunning)?.copy(countdown = targetTime, countdownLabel = step.label) ?: it
            }
            notificationUI?.showCountdown(targetTime, step.label)
            return
        }
        (serviceState.value as? ServiceRunning)?.let {
            (it.cameraState as? CameraStateReady)?.let { cameraState ->
                checkWaitAction(cameraState)
            }
        }
    }

    @Synchronized
    fun countdownActionComplete() {
        _serviceState.update {
            (it as? ServiceRunning)?.copy(countdown = null, countdownLabel = null) ?: it
        }
        notificationUI?.hideCountdown()
        val nextAction = pendingActionSteps.peek()
        if (nextAction is CACountdown) {
            pendingActionSteps.removeFirst()
            executeNextCameraActionStep()
        }
    }

    @Synchronized
    fun checkWaitAction(state: CameraStateReady) {
        val nextAction = pendingActionSteps.peek()
        if (nextAction is CAWaitFor) {
            when (nextAction.target) {
                WaitTarget.FOCUS -> if (state.focus) {
                    pendingActionSteps.removeFirst()
                    executeNextCameraActionStep()
                }
                WaitTarget.SHUTTER -> if (state.shutter) {
                    pendingActionSteps.removeFirst()
                    executeNextCameraActionStep()
                }
                WaitTarget.RECORDING -> if (state.shutter) {
                    pendingActionSteps.removeFirst()
                    executeNextCameraActionStep()
                }
            }
        }
    }

    fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(MainActivity.TAG, "Start location updates: Location permission not granted")
            return
        }
        Log.d(MainActivity.TAG, "Start location updates")
        if (locationManager == null) {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        }
        val request = LocationRequest.Builder(10000L)
            .setMinUpdateIntervalMillis(10000L)
            .build()
        locationManager?.requestLocationUpdates(
            LocationManager.FUSED_PROVIDER,
            request,
            ContextCompat.getMainExecutor(this),
            locationListener
        )
    }

    fun stopLocationUpdates() {
        Log.d(MainActivity.TAG, "Stop location updates")
        locationManager?.removeUpdates(locationListener)
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(MainActivity.TAG, "Latitude: ${location.latitude}, Longitude: ${location.longitude}")
            cameraBLE?.sendLocation(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
}