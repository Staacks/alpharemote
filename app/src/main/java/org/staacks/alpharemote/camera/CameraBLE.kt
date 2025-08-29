package org.staacks.alpharemote.camera

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.staacks.alpharemote.MainActivity
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.experimental.and

// Massive thanks to coral for the documentation of the camera's BLE protocol at
// https://github.com/coral/freemote
// and to Greg Leeds at
// https://gregleeds.com/reverse-engineering-sony-camera-bluetooth/

class CameraBLE(val scope: CoroutineScope, context: Context, val address: String, val onConnect: () -> Unit, val onDisconnect: () -> Unit) {

    val genericAccessServiceUUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")!!
    val nameCharacteristicUUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")!!

    val remoteServiceUUID = UUID.fromString("8000ff00-ff00-ffff-ffff-ffffffffffff")!!
    val commandCharacteristicUUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")!!
    val statusCharacteristicUUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")!!

    val cameraServiceUUID = UUID.fromString("8000cc00-cc00-ffff-ffff-ffffffffffff")!!
    val cameraStatusCharacteristicUUID = UUID.fromString("0000cc09-0000-1000-8000-00805f9b34fb")!!
    val cameraMediaCharacteristicUUID = UUID.fromString("0000cc0f-0000-1000-8000-00805f9b34fb")!!
    val cameraBatteryCharacteristicUUID = UUID.fromString("0000cc10-0000-1000-8000-00805f9b34fb")!!

    val locationServiceUUID = UUID.fromString("8000dd00-dd00-ffff-ffff-ffffffffffff")!!
    val locationNotificationCharacteristicUUID = UUID.fromString("0000dd01-0000-1000-8000-00805f9b34fb")!!
    val locationReceiverCharacteristicUUID = UUID.fromString("0000dd11-0000-1000-8000-00805f9b34fb")!!
    val locationDataFormatCharacteristicUUID = UUID.fromString("0000dd21-0000-1000-8000-00805f9b34fb")!!
    val locationLockCharacteristicUUID = UUID.fromString("0000dd30-0000-1000-8000-00805f9b34fb")!!
    val locationEnabledCharacteristicUUID = UUID.fromString("0000dd31-0000-1000-8000-00805f9b34fb")!!
    val locationTimeCorrectionCharacteristicUUID = UUID.fromString("0000dd32-0000-1000-8000-00805f9b34fb")!!
    val locationAreaAdjustmentCharacteristicUUID = UUID.fromString("0000dd33-0000-1000-8000-00805f9b34fb")!!

    private val configDescriptorUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")!!

    var remoteService: BluetoothGattService? = null
    var remoteCommandCharacteristic: BluetoothGattCharacteristic? = null
    var remoteStatusCharacteristic: BluetoothGattCharacteristic? = null

    var cameraService: BluetoothGattService? = null
    var cameraStatusCharacteristic: BluetoothGattCharacteristic? = null
    var cameraMediaCharacteristic: BluetoothGattCharacteristic? = null
    var cameraBatteryCharacteristic: BluetoothGattCharacteristic? = null

    var locationService: BluetoothGattService? = null
    var locationNotificationCharacteristic: BluetoothGattCharacteristic? = null
    var locationReceiverCharacteristic: BluetoothGattCharacteristic? = null
    var locationDataFormatCharacteristic: BluetoothGattCharacteristic? = null
    var locationLockCharacteristic: BluetoothGattCharacteristic? = null
    var locationEnabledCharacteristic: BluetoothGattCharacteristic? = null
    var locationTimeCorrectionCharacteristic: BluetoothGattCharacteristic? = null
    var locationAreaAdjustmentCharacteristic: BluetoothGattCharacteristic? = null

    private val operationQueue = ConcurrentLinkedQueue<CameraBLEOperation>()
    private var currentOperation: CameraBLEOperation? = null

    private val _cameraState = MutableStateFlow<CameraState>(CameraStateGone())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private var name: String? = null

    private var locationSupportedByCamera = false
    private var locationSendTimezone: Boolean? = null
    private var locationInitDone = false
    private var lastLocation: Location? = null

    private var bluetoothAdapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(MainActivity.TAG, "onConnectionStateChange: status $status, newState $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnect()
                try {
                    gatt?.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(MainActivity.TAG, e.toString())
                    _cameraState.value = CameraStateError(e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                notifyDisconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(MainActivity.TAG, "onServicesDiscovered")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                remoteService = gatt?.getService(remoteServiceUUID)
                remoteCommandCharacteristic = remoteService?.getCharacteristic(commandCharacteristicUUID)
                remoteStatusCharacteristic = remoteService?.getCharacteristic(statusCharacteristicUUID)
                cameraService = gatt?.getService(cameraServiceUUID)
                cameraStatusCharacteristic = cameraService?.getCharacteristic(cameraStatusCharacteristicUUID)
                cameraMediaCharacteristic = cameraService?.getCharacteristic(cameraMediaCharacteristicUUID)
                cameraBatteryCharacteristic = cameraService?.getCharacteristic(cameraBatteryCharacteristicUUID)
                locationService = gatt?.getService(locationServiceUUID)
                locationNotificationCharacteristic = locationService?.getCharacteristic(locationNotificationCharacteristicUUID)
                locationReceiverCharacteristic = locationService?.getCharacteristic(locationReceiverCharacteristicUUID)
                locationDataFormatCharacteristic = locationService?.getCharacteristic(locationDataFormatCharacteristicUUID)
                locationLockCharacteristic = locationService?.getCharacteristic(locationLockCharacteristicUUID)
                locationEnabledCharacteristic = locationService?.getCharacteristic(locationEnabledCharacteristicUUID)
                locationTimeCorrectionCharacteristic = locationService?.getCharacteristic(locationTimeCorrectionCharacteristicUUID)
                locationAreaAdjustmentCharacteristic = locationService?.getCharacteristic(locationAreaAdjustmentCharacteristicUUID)
                Log.d(MainActivity.TAG, "remote=" + (remoteService != null) + ", rCmd=" + (remoteCommandCharacteristic != null) + ", rStatus=" + (remoteStatusCharacteristic != null))
                Log.d(MainActivity.TAG, "camera=" + (cameraService != null) + ", cStatus=" + (cameraStatusCharacteristic != null) + ", cMedia=" + (cameraMediaCharacteristic != null) + ", cBattery=" + (cameraBatteryCharacteristic != null))
                Log.d(MainActivity.TAG, "location=" + (locationService != null) + ", lNotif=" + (locationNotificationCharacteristic != null) + ", lRecv=" + (locationReceiverCharacteristic != null) + ", lDataFormat=" + (locationDataFormatCharacteristic != null) + ", lLock=" + (locationLockCharacteristic != null) + ", lEnabled=" + (locationEnabledCharacteristic != null) + ", lTimeC=" + (locationTimeCorrectionCharacteristic != null) + ", lAreaA=" + (locationAreaAdjustmentCharacteristic != null))
                locationSupportedByCamera = (locationReceiverCharacteristic != null) && (locationDataFormatCharacteristic != null) && (locationLockCharacteristic != null) && (locationEnabledCharacteristic != null) && (locationTimeCorrectionCharacteristic != null) && (locationAreaAdjustmentCharacteristic != null)
                val nameCharacteristic = gatt?.getService(genericAccessServiceUUID)?.getCharacteristic(nameCharacteristicUUID)
                if (remoteStatusCharacteristic != null && remoteCommandCharacteristic != null && nameCharacteristic != null) {
                    remoteStatusCharacteristic?.let {
                        enqueueOperation(CameraBLERead(nameCharacteristic){ status, value ->
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                val newName = value.toString(Charsets.UTF_8)
                                _cameraState.value = CameraStateIdentified(newName, address)
                                name = newName
                            } // Fail state ignored. This commonly fails if a camera sends an onStateChange few ms after connection and Android restarts service discovery just when we are trying to read this value. After the new service discovery we will get back here anyway. If this fails for a different reason, not knowing the model name is not fatal.
                        })
                        enqueueOperation(CameraBLESubscribe(it))
                    }
                    if (cameraService != null && cameraStatusCharacteristic != null && cameraMediaCharacteristic != null && cameraBatteryCharacteristic != null) {
                        cameraMediaCharacteristic?.let {
                            enqueueOperation(CameraBLESubscribe(it))
                            enqueueOperation(CameraBLERead(it){ status, value ->
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    Log.d(MainActivity.TAG, "Initial read of media status succeeded")
                                    onCameraMediaUpdate(value)
                                }
                                else {
                                    Log.w(MainActivity.TAG, "Initial read of media status failed ${status}")
                                }
                            })
                        }
                        cameraBatteryCharacteristic?.let {
                            enqueueOperation(CameraBLESubscribe(it))
                            enqueueOperation(CameraBLERead(it){ status, value ->
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    Log.d(MainActivity.TAG, "Initial read of battery status succeeded")
                                    onCameraBatteryUpdate(value)
                                }
                                else {
                                    Log.w(MainActivity.TAG, "Initial read of battery status failed ${status}")
                                }
                            })
                        }
                    }
                    locationDataFormatCharacteristic?.let {
                        enqueueOperation(CameraBLERead(it){ status, value ->
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                locationSendTimezone = value.size >= 5 && value[4] and 2.toByte() == 2.toByte()
                                Log.d(MainActivity.TAG, "Reading location data format: sendTimezone=${locationSendTimezone}")
                            }
                            else {
                                Log.w(MainActivity.TAG, "Reading location data format failed ${status}")
                                locationSupportedByCamera = false
                            }
                        })
                    }
                    locationLockCharacteristic?.let {
                        Log.d(MainActivity.TAG, "Writing location lock")
                        enqueueOperation(CameraBLEWrite(it, byteArrayOf(0x01.toByte())))
                    }
                    locationEnabledCharacteristic?.let {
                        Log.d(MainActivity.TAG, "Writing location enabled")
                        enqueueOperation(CameraBLEWrite(it, byteArrayOf(0x01.toByte())))
                    }
                } else {
                    _cameraState.value = CameraStateError(null, "Remote service not found.")
                    Log.e(MainActivity.TAG, "remoteService: " + remoteService.toString())
                    Log.e(MainActivity.TAG, "commandCharacteristic: " + remoteCommandCharacteristic.toString())
                    Log.e(MainActivity.TAG, "statusCharacteristic: " + remoteStatusCharacteristic.toString())
                    Log.e(MainActivity.TAG, "nameCharacteristic: " + nameCharacteristic.toString())
                    notifyDisconnect()
                }
            } else {
                Log.e(MainActivity.TAG, "discovery failed: $status")
                _cameraState.value = CameraStateError(null, "Service discovery failed.")
                //Note, at this point the service will not be usable, but we stay connected as this might be recoverable.
                //In fact, newer cameras seem to send an onServiceChanged to bonded devices after few ms, which triggers Android to restart discovery.
                //If this was the reason for this discovery to fail, onServiceChanged will be called soon where discoverServices will be called again.
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            Log.d(MainActivity.TAG, "onServiceChanged")
            resetOperationQueue()
            try {
                gatt.discoverServices()
            }  catch (e: SecurityException) {
                Log.e(MainActivity.TAG, e.toString())
                _cameraState.value = CameraStateError(e)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            cameraBLEWriteComplete(characteristic, status)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                return //Use the new version of onCharacteristicRead instead
            Log.d(MainActivity.TAG, "Deprecated onCharacteristicRead with status $status from ${characteristic.uuid}.")
            cameraBLEReadComplete(status, characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            Log.d(MainActivity.TAG, "onCharacteristicRead with status $status from ${characteristic.uuid}.")
            cameraBLEReadComplete(status, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            cameraBLESubscribeComplete(status)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                return //Use the new version of onCharacteristicRead instead
            Log.d(MainActivity.TAG, "Deprecated onCharacteristicChanged from ${characteristic.uuid}.")
            when (characteristic) {
                remoteStatusCharacteristic -> onRemoteStatusUpdate(characteristic.value)
                cameraStatusCharacteristic -> onCameraStatusUpdate(characteristic.value)
                cameraMediaCharacteristic -> onCameraMediaUpdate(characteristic.value)
                cameraBatteryCharacteristic -> onCameraBatteryUpdate(characteristic.value)
                locationNotificationCharacteristic -> return // TODO, maybe needed for turning the location feature on and off
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            Log.d(MainActivity.TAG, "onCharacteristicChanged from ${characteristic.uuid}.")
            when (characteristic) {
                remoteStatusCharacteristic -> onRemoteStatusUpdate(value)
                cameraStatusCharacteristic -> onCameraStatusUpdate(value)
                cameraMediaCharacteristic -> onCameraMediaUpdate(value)
                cameraBatteryCharacteristic -> onCameraBatteryUpdate(value)
                locationNotificationCharacteristic -> return // TODO
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(MainActivity.TAG, "CameraBLE received BluetoothDevice.ACTION_BOND_STATE_CHANGED.")
            try {
                if (cameraState.value is CameraStateReady && device?.bondState != BluetoothDevice.BOND_BONDED) {
                    _cameraState.value = CameraStateNotBonded()
                    Log.e(MainActivity.TAG, "Camera became unbonded while in use.")
                } else if (cameraState.value is CameraStateNotBonded && device?.bondState == BluetoothDevice.BOND_BONDED) {
                    Log.e(MainActivity.TAG, "Camera is now bonded.")
                    connectToDevice(context)
                }
            } catch (e: SecurityException) {
                _cameraState.value = CameraStateError(e, e.toString())
                Log.e(MainActivity.TAG, e.toString())
            }
        }
    }

    init {
        Log.d(MainActivity.TAG, "init")
        context.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        connectToDevice(context)
    }

    fun connectToDevice(context: Context) {
        Log.d(MainActivity.TAG, "connectToDevice")
        try {
            device = bluetoothAdapter.getRemoteDevice(address)
            if (device?.bondState == BluetoothDevice.BOND_BONDED) {
                _cameraState.value = CameraStateConnecting()
                gatt = device?.connectGatt(context, true, bluetoothGattCallback)
                locationInitDone = false
            } else {
                _cameraState.value = CameraStateNotBonded()
                Log.e(MainActivity.TAG, "Camera found, but not bonded.")
            }
        } catch (e: SecurityException) {
            _cameraState.value = CameraStateError(e, e.toString())
            Log.e(MainActivity.TAG, e.toString())
        }
    }

    fun notifyDisconnect() {
        Log.d(MainActivity.TAG, "notifyDisconnect")
        _cameraState.value = CameraStateGone()
        remoteService = null
        remoteCommandCharacteristic = null
        remoteStatusCharacteristic = null
        resetOperationQueue()
        currentOperation = null
        locationInitDone = false
        onDisconnect()
    }

    fun disconnectFromDevice() {
        Log.d(MainActivity.TAG, "disconnectFromDevice")
        try {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        } catch (e: SecurityException) {
            Log.e(MainActivity.TAG, e.toString())
            _cameraState.value = CameraStateError(e)
        }
        notifyDisconnect()
    }

    @Synchronized
    fun enqueueOperation(operation: CameraBLEOperation) {
        operationQueue.add(operation)
        if (currentOperation == null) {
            executeNextOperation()
        }
    }

    @Synchronized
    fun resetOperationQueue() {
        operationQueue.clear()
        currentOperation = null
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Synchronized
    fun executeNextOperation() {
        if (currentOperation != null)
            return

        currentOperation = operationQueue.poll()

        try {
            when (currentOperation) {
                is CameraBLEWrite -> {
                    val op = currentOperation as CameraBLEWrite
                    Log.d(MainActivity.TAG, "Writing: 0x${op.data.toHexString()}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt?.writeCharacteristic(op.characteristic, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        op.characteristic.setValue(op.data)
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        gatt?.writeCharacteristic(op.characteristic)
                    }
                }

                is CameraBLERead -> {
                    val op = currentOperation as CameraBLERead
                    Log.d(MainActivity.TAG, "Reading from: ${op.characteristic.uuid}")
                    gatt?.readCharacteristic(op.characteristic)
                }
                is CameraBLESubscribe -> {
                    val op = currentOperation as CameraBLESubscribe
                    Log.d(MainActivity.TAG, "Subscribing to: ${op.characteristic.uuid}")
                    gatt?.setCharacteristicNotification(op.characteristic, true)
                    val descriptor = op.characteristic.getDescriptor(configDescriptorUUID)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        descriptor?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        gatt?.writeDescriptor(descriptor)
                    }
                }

                else -> return
            }
        } catch (e: SecurityException) {
            _cameraState.value = CameraStateError(e)
            Log.e(MainActivity.TAG, e.toString())
        }
    }

    @Synchronized
    fun operationComplete() {
        currentOperation = null
        executeNextOperation()
    }

    fun cameraBLEWriteComplete(characteristic: BluetoothGattCharacteristic?, status: Int) {
        Log.d(MainActivity.TAG, "Writing complete: $status")
        if (currentOperation is CameraBLEWrite) {
            operationComplete()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic) {
                    locationLockCharacteristic -> Log.d(MainActivity.TAG, "Writing location lock succeeded")
                    locationEnabledCharacteristic -> {
                        Log.d(MainActivity.TAG, "Writing location enable succeeded")
                        locationInitDone = true
                        lastLocation?.let { sendLocation(it) }
                    }
                }
            }
            if (status == 144) {
                when (characteristic) {
                    //The command failed. This is very likely a properly bonded camera with BLE remote setting disabled
                    remoteCommandCharacteristic -> _cameraState.value = CameraStateRemoteDisabled()
                    locationLockCharacteristic -> Log.w(MainActivity.TAG, "Writing location lock failed") // If a different BLE device was sending location updates to the camera before this could fail. Try unpairing the other device or disabling location linkage on the other device.
                    locationEnabledCharacteristic -> Log.w(MainActivity.TAG, "Writing location enable failed")
                }
            } //Other results are ignored. If this fails for any other reason - well if the button was not pressed, the user has to try again, but it does not change anything for this app.
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun cameraBLEReadComplete(status: Int, value: ByteArray) {
        Log.d(MainActivity.TAG, "cameraBLEReadComplete: $status, 0x${value.toHexString()}")
        if (currentOperation is CameraBLERead) {
            val callback = (currentOperation as CameraBLERead).resultCallback
            operationComplete()
            callback(status, value)
        }
    }

    fun cameraBLESubscribeComplete(status: Int) {
        Log.d(MainActivity.TAG, "cameraBLESubscribeComplete: $status")
        if (currentOperation is CameraBLESubscribe) { //Note: We do not check the status. If subscribing failed for some reason, the camera status is not reported. If this is due to a disconnect, the service will be terminated anyway, but if there is another reason, the rest of the app might still be usable
            val name = (cameraState.value as? CameraStateIdentified)?.name
            if (name == null)
                Log.w(MainActivity.TAG, "Subscribe complete, but camera in unidentified state.")
            _cameraState.value = CameraStateReady(name, focus = ReportedBoolean(), shutter = ReportedBoolean(), recording = ReportedBoolean(), emptySet(), emptySet(), null, null)
            operationComplete()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun onRemoteStatusUpdate(value: ByteArray) {
        _cameraState.update {
            if (it is CameraStateRemoteDisabled || it is CameraStateReady) {
                val state = if (it is CameraStateRemoteDisabled)
                    // The remote disabled state is the consequence of a failed write. This might be recoverable (i.e. user turned on the remote feature), so let's start with a fresh ready state.
                    CameraStateReady(name, focus = ReportedBoolean(), shutter = ReportedBoolean(), recording = ReportedBoolean(), emptySet(), emptySet(), null, null)
                else
                    it as CameraStateReady
                when (value[1]) {
                    0x3f.toByte() -> state.copy(focus = ReportedBoolean(value[2].and(0x20.toByte()) != 0.toByte()))
                    0xa0.toByte() -> state.copy(shutter = ReportedBoolean(value[2].and(0x20.toByte()) != 0.toByte()))
                    0xd5.toByte() -> state.copy(recording = ReportedBoolean(value[2].and(0x20.toByte()) != 0.toByte()))
                    else -> state
                }
            } else // This should not happen. If it happens, it is probably the result of the BLE communication running in parallel to whatever changed the state. In this case it is probably not recoverable and should be ignored
                it
        }
        Log.d(MainActivity.TAG, "Received remote status: 0x${value.toHexString()}")
    }

    fun parseCameraMedia(data: ByteArray): CameraMediaStatus? {
        if (data.size < 20 || data[1] != 0.toByte() || data[2] != 0.toByte() || data[3] != 2.toByte()) {
            return null
        }
        val slot1ShotsRemaining = if ((data[4] and 2.toByte()) == 2.toByte())
            ByteBuffer.wrap(byteArrayOf(data[8], data[9], data[10], data[11])).int
        else
            null
        val slot1SecondsRemaining = if ((data[4] and 4.toByte()) == 4.toByte())
            ByteBuffer.wrap(byteArrayOf(data[16], data[17], data[18], data[19])).int
        else
            null
        if ((data[4] and 1.toByte()) == 1.toByte() && data[6].toInt() in 1..5 && (slot1ShotsRemaining != null || slot1SecondsRemaining != null)) {
            val slot1Description = if (slot1SecondsRemaining == null) "\uD83D\uDCF7$slot1ShotsRemaining" else "\uD83C\uDFA5${DateUtils.formatElapsedTime(slot1SecondsRemaining.toLong())}"
            return CameraMediaStatus(slot1ShotsRemaining, slot1SecondsRemaining, slot1Description)
        }
        if (data.size < 24) {
            return null
        }
        val slot2ShotsRemaining = if ((data[5] and 2.toByte()) == 2.toByte())
            ByteBuffer.wrap(byteArrayOf(data[12], data[13], data[14], data[15])).int
        else
            null
        val slot2SecondsRemaining = if ((data[5] and 4.toByte()) == 4.toByte())
            ByteBuffer.wrap(byteArrayOf(data[20], data[21], data[22], data[23])).int
        else
            null
        if ((data[5] and 1.toByte()) == 1.toByte() && data[7].toInt() in 1..5 && (slot2ShotsRemaining != null || slot2SecondsRemaining != null)) {
            val slot2Description = if (slot2SecondsRemaining == null) "\uD83D\uDCF7$slot2ShotsRemaining" else "\uD83C\uDFA5${DateUtils.formatElapsedTime(slot2SecondsRemaining.toLong())}"
            return CameraMediaStatus(slot2ShotsRemaining, slot2SecondsRemaining, slot2Description)
        }
        return null
    }

    fun parseCameraBattery(data: ByteArray): CameraBatteryStatus? {
        if (data.size < 18 || data[1] != 0.toByte() || data[2] != 0.toByte() || data[3] != 2.toByte()) {
            return null
        }
        val pack1Percentage = ByteBuffer.wrap(byteArrayOf(data[10], data[11], data[12], data[13])).int
        val pack1Charging = (data[8].toInt() in 6..11)
        if ((data[4] and 1.toByte()) == 1.toByte() && data[8].toInt() in 1..11 && pack1Percentage in 0..100) {
            return CameraBatteryStatus(
                percentage = pack1Percentage,
                charging = pack1Charging,
                description = "${if (pack1Charging) "⚡" else "\uD83D\uDD0B"}${pack1Percentage}%"
            )
        }
        val pack2Percentage = ByteBuffer.wrap(byteArrayOf(data[14], data[15], data[16], data[17])).int
        val pack2Charging = (data[9].toInt() in 6..11)
        if ((data[5] and 1.toByte()) == 1.toByte() && data[9].toInt() in 1..11 && pack2Percentage in 0..100) {
            return CameraBatteryStatus(
                percentage = pack2Percentage,
                charging = pack2Charging,
                description = "${if (pack2Charging) "⚡" else "\uD83D\uDD0B"}${pack2Percentage}%"
            )
        }
        return null
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun onCameraStatusUpdate(value: ByteArray) {
        Log.d(MainActivity.TAG, "Received camera status: 0x${value.toHexString()}")
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun onCameraMediaUpdate(value: ByteArray) {
        Log.d(MainActivity.TAG, "Received camera media: 0x${value.toHexString()}")
        var mediaStatus = parseCameraMedia(value)
        mediaStatus?.let {
            Log.d(MainActivity.TAG, "Media Shots: ${it.shotsRemaining}, Seconds: ${it.secondsRemaining}, Description: ${it.description}")
        }
        _cameraState.update {
            if (it is CameraStateReady) {
                it.copy(mediaStatus = mediaStatus)
            } else {
                it
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun onCameraBatteryUpdate(value: ByteArray) {
        Log.d(MainActivity.TAG, "Received camera battery: 0x${value.toHexString()}")
        var batteryStatus = parseCameraBattery(value)
        batteryStatus?.let {
            Log.d(MainActivity.TAG, "Battery percentage: ${it.percentage}, Charging: ${it.charging}, Description: ${it.description}")
        }
        _cameraState.update {
            if (it is CameraStateReady) {
                it.copy(batteryStatus = batteryStatus)
            } else {
                it
            }
        }
    }

    fun serializeLocation(location: Location): ByteArray? {
        // Check if location timestamp is not too old
        if (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 30000000000) {
            Log.w(MainActivity.TAG, "Location too old")
            return null
        }

        val sendTimezone = locationSendTimezone
        if (sendTimezone == null) { return null }

        // Initialize data as bytes
        val dataLength = if (sendTimezone) 95 else 91
        val result = ByteArray(dataLength)

        // Set initial values in the data array
        byteArrayOf(
            0x00.toByte(), 0x00.toByte(), 0x08.toByte(), 0x02.toByte(), 0xfc.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x10.toByte(), 0x10.toByte(), 0x10.toByte()
        ).copyInto(result)
        result[1] = (dataLength - 2).toByte()
        result[5] = if (sendTimezone) 0x03.toByte() else 0x00.toByte()

        // Pack latitude and longitude into bytes
        ByteBuffer.allocate(4).putInt((location.latitude * 10000000).toInt()).array().copyInto(result, 11)
        ByteBuffer.allocate(4).putInt((location.longitude * 10000000).toInt()).array().copyInto(result, 15)

        // Pack date and time into bytes
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        //calendar.timeInMillis = location.time
        ByteBuffer.allocate(2).putShort((calendar.get(Calendar.YEAR)).toShort()).array().copyInto(result, 19)
        result[21] = calendar.get(Calendar.MONTH).plus(1).toByte()
        result[22] = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        result[23] = calendar.get(Calendar.HOUR_OF_DAY).toByte()
        result[24] = calendar.get(Calendar.MINUTE).toByte()
        result[25] = calendar.get(Calendar.SECOND).toByte()

        if (sendTimezone) {
            // Pack time zone and DST offsets into bytes
            ByteBuffer.allocate(2).putShort(
                (TimeZone.getDefault().rawOffset / 60000).toShort()
            ).array().copyInto(result, 91)
            ByteBuffer.allocate(2).putShort(
                (if (TimeZone.getDefault().inDaylightTime(Date()))
                    TimeZone.getDefault().dstSavings / 60000
                else
                    0
                ).toShort()
            ).array().copyInto(result, 93)
        }
        return result
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendLocation(location: Location) {
        if (locationSupportedByCamera && locationInitDone && locationSendTimezone != null) {
            serializeLocation(location)?.let { data ->
                locationReceiverCharacteristic?.let { characteristic ->
                    Log.d(MainActivity.TAG, "Sending location data: 0x${data.toHexString()}")
                    enqueueOperation(CameraBLEWrite(characteristic, data))
                    lastLocation = null
                }
            }
        } else {
            Log.d(MainActivity.TAG, "Saving location until init is done")
            lastLocation = location
        }
    }

    fun executeCameraActionStep(action: CameraActionStep) {
        Log.d(MainActivity.TAG, "executeCameraActionStep")
        if (cameraState.value !is CameraStateReady)
            return
        try {
            remoteCommandCharacteristic?.let { char ->
                when (action) {
                    is CAButton -> {
                        enqueueOperation(CameraBLEWrite(char, byteArrayOf(0x01, action.getCode())))
                        _cameraState.update {
                            (it as? CameraStateReady)?.copy(
                                pressedButtons = if (action.pressed) it.pressedButtons + action.button else it.pressedButtons - action.button
                            ) ?: it
                        }
                    }
                    is CAJog -> {
                        enqueueOperation(CameraBLEWrite(char, byteArrayOf(0x02, action.getCode(), if (action.pressed) action.step else 0x00)))
                        _cameraState.update {
                            (it as? CameraStateReady)?.copy(
                                pressedJogs = if (action.pressed) it.pressedJogs + action.jog else it.pressedJogs - action.jog
                            ) ?: it
                        }
                    }
                    else -> Unit //Countdown and wait for event are handled by service
                }
            }
        } catch (e: SecurityException) {
            _cameraState.value = CameraStateError(e)
            Log.e(MainActivity.TAG, e.toString())
        }
    }
}