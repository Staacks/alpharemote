package org.staacks.alpharemote.service

import org.staacks.alpharemote.camera.CameraState

sealed class ServiceState

class ServiceStateGone : ServiceState()

data class ServiceRunning(
    val cameraState: CameraState,
    val countdown: Long?,
    val countdownLabel: String?,
    val pendingTriggerCount: Int = 0,
) : ServiceState()
