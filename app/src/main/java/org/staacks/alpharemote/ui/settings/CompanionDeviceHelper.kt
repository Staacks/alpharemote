package org.staacks.alpharemote.ui.settings

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.staacks.alpharemote.MainActivity
import org.staacks.alpharemote.utils.hasBluetoothPermission

//Massive thanks to coral for the documentation of the camera's BLE protocol at
// https://github.com/coral/freemote

object CompanionDeviceHelper {

    fun getAssociation(context: Context): List<String> {
        val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        return deviceManager.associations
    }

    fun pairCompanionDevice(context: Context, callback: CompanionDeviceManager.Callback) {
        val deviceFilter: BluetoothLeDeviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder()
                .setManufacturerData(
                    0x012d,
                    byteArrayOf(            //Filter raw values
                        0x03.toByte(), 0x00.toByte(),               //Camera
                        0x64.toByte(),                              //Protocol version
                        0x00.toByte(),                              //Unknown
                        0x00.toByte(), 0x00.toByte(),               //Model
                        0x22.toByte(), 0x40.toByte(), 0x00.toByte() //Camera state, bit 0x40 indicates ready to pair
                    ),
                    byteArrayOf(            //Filter bit mask
                        0xff.toByte(), 0xff.toByte(),               //Camera
                        0x00.toByte(),                              //Protocol version. For now we will ignore this as older models like the a6400 report 0x64 and models from around 2023 report 0x65. No idea what the differences are and when compatibility will break, but so far there does not seem to be a camera with Bluetooth support that is not compatible with the standard BLE remote from Sony. So, for now this seems to be fine.
                        0x00.toByte(),                              //Unknown
                        0x00.toByte(), 0x00.toByte(),               //Model
                        0xff.toByte(), 0x40.toByte(), 0x00.toByte() //Camera state, bit 0x40,  indicates ready to pair
                    )
                    //TODO: We might be able to check if the remote control feature is enabled on the camera here.
                    //Coral claims that 0x02 of the 8th byte indicates the remote control feature.
                    //(see https://github.com/coral/freemote/blob/14cfde8dcf6576b4fad6c2283c19c650c44f8e61/src/BLECamera.cpp#L237)
                    //However, my a6400 advertises 0x03006400453122ec00... with the remote enabled and
                    //with remote disabled it is   0x03006400453122e800...
                    //This would suggest that we have to check for 0x04.
                    //So, until we can verify this on a few different models, we do not check this bit to ensure compatibility
                )
                .build()
            )
            .build()

        val associationRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()

        val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        Log.d(MainActivity.TAG, "Associating.")
        deviceManager.associate(associationRequest, callback, null)
    }

    fun startObservingDevicePresence(context: Context, device: BluetoothDevice): Boolean {
        try {
            device.createBond()
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                ContextCompat.getSystemService(context,CompanionDeviceManager::class.java)?.startObservingDevicePresence(
                    device.address
                )
                return true
            }
        } catch (e: SecurityException) {
            Log.e(MainActivity.TAG, e.toString())
            //This should be impossible as we check the permission before attempting to pair with the companion device.
        }
        Log.e(MainActivity.TAG, "Failed to observe device presence.")
        return false
    }

    fun unpairCompanionDevice(context: Context) {
        val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            for (association in deviceManager.myAssociations) {
                Log.d(MainActivity.TAG, "Disassociating ${association.deviceMacAddress}.")
                val request = ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build()
                deviceManager.stopObservingDevicePresence(request)
                if (hasBluetoothPermission(context))
                    deviceManager.removeBond(association.id)
                deviceManager.disassociate(association.id)
            }
        } else {
            for (address in deviceManager.associations) {
                Log.d(MainActivity.TAG, "Disassociating $address.")
                deviceManager.stopObservingDevicePresence(address)
                deviceManager.disassociate(address)
            }
        }
    }
}