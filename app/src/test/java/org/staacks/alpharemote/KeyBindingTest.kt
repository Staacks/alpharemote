package org.staacks.alpharemote

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class KeyBindingTest {

    @Test
    fun testCameraActionSerializationWithKeyCode() {
        val original = CameraAction(
            toggle = true,
            selftimer = 3.0f,
            duration = 5.0f,
            step = 0.5f,
            preset = CameraActionPreset.RECORD,
            keyCode = KeyEvent.KEYCODE_VOLUME_UP
        )

        val byteArrayOutputStream = ByteArrayOutputStream()
        ObjectOutputStream(byteArrayOutputStream).use { it.writeObject(original) }

        val deserialized = ObjectInputStream(ByteArrayInputStream(byteArrayOutputStream.toByteArray())).use {
            it.readObject() as CameraAction
        }

        assertEquals(original, deserialized)
    }
}
