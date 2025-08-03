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
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val configDescriptorUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")!!

    var remoteService: BluetoothGattService? = null
    var commandCharacteristic: BluetoothGattCharacteristic? = null
    var statusCharacteristic: BluetoothGattCharacteristic? = null

    private val operationQueue = ConcurrentLinkedQueue<CameraBLEOperation>()
    private var currentOperation: CameraBLEOperation? = null

    private val _cameraState = MutableStateFlow<CameraState>(CameraStateGone())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private var name: String? = null

    private var bluetoothAdapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status $status, newState $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnect()
                try {
                    gatt?.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(TAG, e.toString())
                    _cameraState.value = CameraStateError(e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                notifyDisconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "onServicesDiscovered")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                remoteService = gatt?.getService(remoteServiceUUID)
                commandCharacteristic = remoteService?.getCharacteristic(commandCharacteristicUUID)
                statusCharacteristic = remoteService?.getCharacteristic(statusCharacteristicUUID)
                val nameCharacteristic = gatt?.getService(genericAccessServiceUUID)?.getCharacteristic(nameCharacteristicUUID)
                if (statusCharacteristic != null && commandCharacteristic != null && nameCharacteristic != null) {
                    statusCharacteristic?.let {
                        enqueueOperation(CameraBLERead(nameCharacteristic){ status, value ->
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                val newName = value.toString(Charsets.UTF_8)
                                _cameraState.value = CameraStateIdentified(newName, address)
                                name = newName
                            } // Fail state ignored. This commonly fails if a camera sends an onStateChange few ms after connection and Android restarts service discovery just when we are trying to read this value. After the new service discovery we will get back here anyway. If this fails for a different reason, not knowing the model name is not fatal.
                        })
                        enqueueOperation(CameraBLESubscribe(it))
                    }
                } else {
                    _cameraState.value = CameraStateError(null, "Remote service not found.")
                    Log.e(TAG, "remoteService: $remoteService")
                    Log.e(TAG, "commandCharacteristic: $commandCharacteristic")
                    Log.e(TAG, "statusCharacteristic: $statusCharacteristic")
                    Log.e(TAG, "nameCharacteristic: $nameCharacteristic")
                    notifyDisconnect()
                }
            } else {
                Log.e(TAG, "discovery failed: $status")
                _cameraState.value = CameraStateError(null, "Service discovery failed.")
                //Note, at this point the service will not be usable, but we stay connected as this might be recoverable.
                //In fact, newer cameras seem to send an onServiceChanged to bonded devices after few ms, which triggers Android to restart discovery.
                //If this was the reason for this discovery to fail, onServiceChanged will be called soon where discoverServices will be called again.
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            Log.d(TAG, "onServiceChanged")
            resetOperationQueue()
            try {
                gatt.discoverServices()
            }  catch (e: SecurityException) {
                Log.e(TAG, e.toString())
                _cameraState.value = CameraStateError(e)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            cameraBLEWriteComplete(status)
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
            Log.d(TAG, "Deprecated onCharacteristicRead with status $status from ${characteristic.uuid}.")
            cameraBLEReadComplete(status, characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            Log.d(TAG, "onCharacteristicRead with status $status from ${characteristic.uuid}.")
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
            Log.d(TAG, "Deprecated onCharacteristicChanged from ${characteristic.uuid}.")
            if (characteristic == statusCharacteristic) {
                onCameraStatusUpdate(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            Log.d(TAG, "onCharacteristicChanged from ${characteristic.uuid}.")
            if (characteristic == statusCharacteristic) {
                onCameraStatusUpdate(value)
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "CameraBLE received BluetoothDevice.ACTION_BOND_STATE_CHANGED.")
            try {
                if (cameraState.value is CameraStateReady && device?.bondState != BluetoothDevice.BOND_BONDED) {
                    _cameraState.value = CameraStateNotBonded()
                    Log.e(TAG, "Camera became unbonded while in use.")
                } else if (cameraState.value is CameraStateNotBonded && device?.bondState == BluetoothDevice.BOND_BONDED) {
                    Log.e(TAG, "Camera is now bonded.")
                    connectToDevice(context)
                }
            } catch (e: SecurityException) {
                _cameraState.value = CameraStateError(e, e.toString())
                Log.e(TAG, e.toString())
            }
        }
    }

    init {
        Log.d(TAG, "init")
        context.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        connectToDevice(context)
    }

    fun connectToDevice(context: Context) {
        Log.d(TAG, "connectToDevice")
        try {
            device = bluetoothAdapter.getRemoteDevice(address)
            if (device?.bondState == BluetoothDevice.BOND_BONDED) {
                _cameraState.value = CameraStateConnecting()
                gatt = device?.connectGatt(context, true, bluetoothGattCallback)
            } else {
                _cameraState.value = CameraStateNotBonded()
                Log.e(TAG, "Camera found, but not bonded.")
            }
        } catch (e: SecurityException) {
            _cameraState.value = CameraStateError(e, e.toString())
            Log.e(TAG, e.toString())
        }
    }

    fun notifyDisconnect() {
        Log.d(TAG, "notifyDisconnect")
        _cameraState.value = CameraStateGone()
        remoteService = null
        commandCharacteristic = null
        statusCharacteristic = null
        resetOperationQueue()
        currentOperation = null
        onDisconnect()
    }

    fun disconnectFromDevice() {
        Log.d(TAG, "disconnectFromDevice")
        try {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        } catch (e: SecurityException) {
            Log.e(TAG, e.toString())
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
                    Log.d(TAG, "Writing: 0x${op.data.toHexString()}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt?.writeCharacteristic(op.characteristic, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        op.characteristic.value =op.data
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        gatt?.writeCharacteristic(op.characteristic)
                    }
                }

                is CameraBLERead -> {
                    val op = currentOperation as CameraBLERead
                    Log.d(TAG, "Reading from: ${op.characteristic.uuid}")
                    gatt?.readCharacteristic(op.characteristic)
                }
                is CameraBLESubscribe -> {
                    val op = currentOperation as CameraBLESubscribe
                    Log.d(TAG, "Subscribing to: ${op.characteristic.uuid}")
                    gatt?.setCharacteristicNotification(op.characteristic, true)
                    val descriptor = op.characteristic.getDescriptor(configDescriptorUUID)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        (descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        @Suppress("DEPRECATION", "Used for backwards compatibility on API<33")
                        gatt?.writeDescriptor(descriptor)
                    }
                }

                else -> return
            }
        } catch (e: SecurityException) {
            _cameraState.value = CameraStateError(e)
            Log.e(TAG, e.toString())
        }
    }

    @Synchronized
    fun operationComplete() {
        currentOperation = null
        executeNextOperation()
    }

    fun cameraBLEWriteComplete(status: Int) {
        Log.d(TAG, "Writing complete: $status")
        if (currentOperation is CameraBLEWrite) {
            operationComplete()
            if (status == 144) {
                //The command failed. This is very likely a properly bonded camera with BLE remote setting disabled
                _cameraState.value = CameraStateRemoteDisabled()
            } //Other results are ignored. If this fails for any other reason - well if the button was not pressed, the user has to try again, but it does not change anything for this app.
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun cameraBLEReadComplete(status: Int, value: ByteArray) {
        Log.d(TAG, "cameraBLEReadComplete: $status, 0x${value.toHexString()}")
        if (currentOperation is CameraBLERead) {
            val callback = (currentOperation as CameraBLERead).resultCallback
            operationComplete()
            callback(status, value)
        }
    }

    fun cameraBLESubscribeComplete(status: Int) {
        Log.d(TAG, "cameraBLESubscribeComplete: $status")
        if (currentOperation is CameraBLESubscribe) { //Note: We do not check the status. If subscribing failed for some reason, the camera status is not reported. If this is due to a disconnect, the service will be terminated anyway, but if there is another reason, the rest of the app might still be usable
            val name = (cameraState.value as? CameraStateIdentified)?.name
            if (name == null)
                Log.w(TAG, "Subscribe complete, but camera in unidentified state.")
            _cameraState.value = CameraStateReady(name, focus = false, shutter = false, recording = false, emptySet(), emptySet())
            operationComplete()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun onCameraStatusUpdate(value: ByteArray) {
        _cameraState.update {
            if (it is CameraStateRemoteDisabled || it is CameraStateReady) {
                val state = if (it is CameraStateRemoteDisabled)
                    // The remote disabled state is the consequence of a failed write. This might be recoverable (i.e. user turned on the remote feature), so let's start with a fresh ready state.
                    CameraStateReady(name, focus = false, shutter = false, recording = false, emptySet(), emptySet())
                else
                    it as CameraStateReady
                when (value[1]) {
                    0x3f.toByte() -> state.copy(focus = (value[2].and(0x20.toByte()) != 0.toByte()))
                    0xa0.toByte() -> state.copy(shutter = (value[2].and(0x20.toByte()) != 0.toByte()))
                    0xd5.toByte() -> state.copy(recording = (value[2].and(0x20.toByte()) != 0.toByte()))
                    else -> state
                }
            } else // This should not happen. If it happens, it is probably the result of the BLE communication running in parallel to whatever changed the state. In this case it is probably not recoverable and should be ignored
                it
        }
        Log.d(TAG, "Received status: 0x${value.toHexString()}")
    }

    fun executeCameraActionStep(action: CameraActionStep) {
        Log.d(TAG, "executeCameraActionStep")
        if (cameraState.value !is CameraStateReady)
            return
        try {
            commandCharacteristic?.let { char ->
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
            Log.e(TAG, e.toString())
        }
    }

    companion object{
        const val TAG = "AlphaRemote-BLE"
    }
}