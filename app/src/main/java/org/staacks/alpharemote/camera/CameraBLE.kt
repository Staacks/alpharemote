package org.staacks.alpharemote.camera

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.experimental.and

// Massive thanks to coral for the documentation of the camera's BLE protocol at
// https://github.com/coral/freemote
// and to Greg Leeds at
// https://gregleeds.com/reverse-engineering-sony-camera-bluetooth/

class CameraBLE(val scope: CoroutineScope, context: Context, val address: String, val onDisconnect: () -> Unit) {

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

    private var bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d("BLE", "onConnectionStateChange: status $status, newState $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt?.discoverServices()
                } catch (e: SecurityException) {
                    Log.e("SecurityException", e.toString())
                    _cameraState.value = CameraStateError(e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnectFromDevice()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d("BLE", "onServicesDiscovered")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                remoteService = gatt?.getService(remoteServiceUUID)
                commandCharacteristic = remoteService?.getCharacteristic(commandCharacteristicUUID)
                statusCharacteristic = remoteService?.getCharacteristic(statusCharacteristicUUID)
                val nameCharacteristic = gatt?.getService(genericAccessServiceUUID)?.getCharacteristic(nameCharacteristicUUID)
                if (statusCharacteristic != null && commandCharacteristic != null && nameCharacteristic != null) {
                    statusCharacteristic?.let {
                        enqueueOperation(CameraBLERead(nameCharacteristic){
                            val newName = it.toString(Charsets.UTF_8)
                            _cameraState.value = CameraStateIdentified(newName, address)
                            name = newName
                        })
                        enqueueOperation(CameraBLESubscribe(it))
                    }
                } else {
                    _cameraState.value = CameraStateError(null, "Remote service not found.")
                    Log.e("serviceNotFound", "remoteService: " + remoteService.toString())
                    Log.e("serviceNotFound", "commandCharacteristic: " + commandCharacteristic.toString())
                    Log.e("serviceNotFound", "statusCharacteristic: " + statusCharacteristic.toString())
                    Log.e("serviceNotFound", "nameCharacteristic: " + nameCharacteristic.toString())
                    disconnectFromDevice()
                }
            } else {
                Log.e("serviceNotFound", "discovery failed: $status")
                disconnectFromDevice()
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

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            cameraBLEReadComplete(status, characteristic.value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            cameraBLESubscribeComplete(status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic == statusCharacteristic) {
                onCameraStatusUpdate(characteristic.value)
            }
        }
    }

    init {
        Log.d("BLE", "init / connectToDevice")
        try {
            device = bluetoothAdapter.getRemoteDevice(address)
            if (device?.bondState == BluetoothDevice.BOND_BONDED) {
                _cameraState.value = CameraStateConnecting()
                gatt = device?.connectGatt(context, false, bluetoothGattCallback)
            } else {
                _cameraState.value = CameraStateNotBonded()
                Log.e("BLE", "Camera found, but not bonded.")
            }
        } catch (e: SecurityException) {
            _cameraState.value = CameraStateError(e, e.toString())
            Log.e("SecurityException", e.toString())
        }
    }

    fun disconnectFromDevice() {
        Log.d("BLE", "disconnectFromDevice")
        try {
            _cameraState.value = CameraStateGone()
            gatt?.close()
            gatt = null
            remoteService = null
            commandCharacteristic = null
            statusCharacteristic = null
            operationQueue.clear()
            currentOperation = null
            onDisconnect()
        } catch (e: SecurityException) {
            Log.e("SecurityException", e.toString())
            _cameraState.value = CameraStateError(e)
            onDisconnect()
        }
    }

    @Synchronized
    fun enqueueOperation(operation: CameraBLEOperation) {
        operationQueue.add(operation)
        if (currentOperation == null) {
            executeNextOperation()
        }
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
                    Log.d("BLE", "Writing: " + op.data.toHexString())
                    op.characteristic.setValue(op.data)
                    gatt?.writeCharacteristic(op.characteristic)
                }

                is CameraBLERead -> {
                    val op = currentOperation as CameraBLERead
                    gatt?.readCharacteristic(op.characteristic)
                }
                is CameraBLESubscribe -> {
                    val op = currentOperation as CameraBLESubscribe
                    gatt?.setCharacteristicNotification(op.characteristic, true)
                    val descriptor = op.characteristic.getDescriptor(configDescriptorUUID)
                    descriptor?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    gatt?.writeDescriptor(descriptor)
                }

                else -> return
            }
        } catch (e: SecurityException) {
            _cameraState.value = CameraStateError(e)
            Log.e("SecurityException", e.toString())
        }
    }

    @Synchronized
    fun operationComplete() {
        currentOperation = null
        executeNextOperation()
    }

    fun cameraBLEWriteComplete(status: Int) {
        Log.d("BLE", "Writing complete: $status")
        if (currentOperation is CameraBLEWrite) {
            operationComplete()
            if (status == 144) {
                //The command failed. This is very likely a properly bonded camera with BLE remote setting disabled
                _cameraState.value = CameraStateRemoteDisabled()
            }
        }
    }

    fun cameraBLEReadComplete(status: Int, value: ByteArray) {
        Log.d("BLE", "cameraBLEReadComplete: $status, $value")
        if (currentOperation is CameraBLERead) {
            val callback = (currentOperation as CameraBLERead).resultCallback
            operationComplete()
            callback(value)
        }
    }

    fun cameraBLESubscribeComplete(status: Int) {
        Log.d("BLE", "cameraBLESubscribeComplete: $status")
        if (currentOperation is CameraBLESubscribe) {
            scope.launch {
                val name = (cameraState.value as? CameraStateIdentified)?.name
                if (name == null)
                    Log.w("BLE", "Subscribe complete, but camera in unidentified state.")
                _cameraState.value = CameraStateReady(name, focus = false, shutter = false, recording = false, emptySet(), emptySet())
            }
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
                    0xd5.toByte() -> state.copy(focus = (value[2].and(0x20.toByte()) != 0.toByte()))
                    else -> state
                }
            } else // This should not happen. If it happens, it is probably the result of the BLE communication running in parallel to whatever changed the state. In this case it is probably not recoverable and should be ignored
                it
        }
        Log.d("BLE", "Received status: " + value.toHexString())
    }

    fun executeCameraActionStep(action: CameraActionStep) {
        Log.d("BLE", "executeCameraActionStep")
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
            Log.e("SecurityException", e.toString())
        }
    }
}