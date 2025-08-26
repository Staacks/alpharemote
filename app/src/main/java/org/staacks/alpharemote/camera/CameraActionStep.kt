package org.staacks.alpharemote.camera

import kotlin.experimental.or

sealed class CameraActionStep

data class CACountdown (
    val label: String,
    val duration: Float
) : CameraActionStep()

data class CAWaitFor (
    val target: WaitTarget
) : CameraActionStep()

data class CAButton (
    val pressed: Boolean,
    val button: ButtonCode,
    val isSequenceTrigger: Boolean = false,
) : CameraActionStep() {
    fun getCode(): Byte {
        return button.code or (if (pressed) 0x01 else 0x00)
    }
}

data class CAJog (
    val pressed: Boolean,
    val step: Byte,
    val jog: JogCode,
) : CameraActionStep() {
    fun getCode(): Byte {
        return jog.code or (if (pressed) 0x01 else 0x00)
    }
}

enum class WaitTarget {
    FOCUS, SHUTTER, RECORDING
}

enum class ButtonCode(val code: Byte) {
    SHUTTER_FULL(0x08),
    SHUTTER_HALF(0x06),
    RECORD(0x0e),
    AF_ON(0x14),
    C1(0x20)
}

enum class JogCode(val code: Byte, val minStep: Byte, val maxStep: Byte) {
    ZOOM_IN(0x44, 0x10, 0x7f),
    ZOOM_OUT(0x46, 0x10, 0x7f),
    FOCUS_NEAR(0x6a, 0x00, 0x7f),
    FOCUS_FAR(0x6c, 0x00, 0x7f)
}