package org.staacks.alpharemote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import org.staacks.alpharemote.MainActivity
import org.staacks.alpharemote.R
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraState
import org.staacks.alpharemote.camera.CameraStateConnecting
import org.staacks.alpharemote.camera.CameraStateError
import org.staacks.alpharemote.camera.CameraStateGone
import org.staacks.alpharemote.camera.CameraStateReady
import java.io.Serializable
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.timerTask
import kotlin.math.roundToInt

class NotificationUI(private val context: Context) {
    private val channelId = "AlphaRemote"
    private val channelName = context.getText(R.string.app_name)
    val notificationId = 1

    private var notificationManager: NotificationManager? = null
    private var notificationBuilder: NotificationCompat.Builder? = null

    private var customButtons: List<CameraAction>? = null
    private var buttonSize: Float = 1.0f
    private var cameraState: CameraState? = null
    private var countDownTime: Long? = null
    private var countDownLabel: String? = null

    private var notifyTimer: Timer? = null //Used to limit the rate at which notify is called
    private var notifyTask: TimerTask? = null

    companion object {
        val buttonIDs = intArrayOf(R.id.button0, R.id.button1, R.id.button2, R.id.button3, R.id.button4, R.id.button5, R.id.button6, R.id.button7, R.id.button8, R.id.button9)
    }

    fun start(): Notification {
        notifyTimer = Timer()

        val notificationChannel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        notificationManager = (context.getSystemService(CompanionDeviceService.NOTIFICATION_SERVICE) as NotificationManager)
        notificationManager?.createNotificationChannel(notificationChannel)

        notificationBuilder = setupNotificationBuilder()

        return notificationBuilder!!.build()
    }

    fun stop() {
        try {
            notifyTask?.cancel()
        } catch (_: IllegalStateException) {}
        notifyTask = null
        try {
            notifyTimer?.cancel()
        } catch (_: IllegalStateException) {}
        notifyTimer = null
        notificationManager?.deleteNotificationChannel(channelId)
        notificationManager = null
    }

    fun updateNotification() {
        notifyTask?.cancel()
        try {
            notifyTask = timerTask {
                notificationBuilder?.let {
                    it.setCustomContentView(createRemoteViews())
                    notificationManager?.notify(notificationId, it.build())
                }
                notifyTask = null
            }
            notifyTimer?.schedule(notifyTask, 200)
        } catch (_: IllegalStateException) {}
    }

    private fun createRemoteViews(): RemoteViews {
        val remoteViews = RemoteViews(context.packageName, R.layout.notification_controls)

        //Using a collection widget with a RemoteViewsService might be an interesting alternative,
        //but since the user will usually setup custom buttons once without touching them again
        //for a long time, this more or less static setup seems more appropriate.
        buttonIDs.forEachIndexed { index, buttonID ->
            customButtons?.getOrNull(index)?.also { cameraAction ->
                remoteViews.setImageViewBitmap(buttonID, getIconBmp(cameraAction,
                    available = true,
                    pressed = false
                ))

                if (countDownTime == null) {
                    val intent = Intent(context, AlphaRemoteService::class.java).apply {
                        action = AlphaRemoteService.BUTTON_INTENT_ACTION
                        putExtra(AlphaRemoteService.BUTTON_INTENT_CAMERA_ACTION_EXTRA, cameraAction as Serializable)
                    }
                    val pendingIntent = PendingIntent.getService(context, index, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    remoteViews.setOnClickPendingIntent(buttonID, pendingIntent)
                }

                remoteViews.setViewVisibility(buttonID, View.VISIBLE)
            } ?: run {
                remoteViews.setViewVisibility(buttonID, View.GONE)
            }
        }

        cameraState?.let { state ->
            if (state is CameraStateReady) {
                val white = context.getColor(R.color.white)
                val black = context.getColor(R.color.black)

                remoteViews.setTextViewText(
                    R.id.status_name,
                    state.name ?: "Camera"
                )

                remoteViews.setColorInt(
                    R.id.status_focus,
                    "setColorFilter",
                    black, white
                )
                remoteViews.setColorInt(
                    R.id.status_shutter,
                    "setColorFilter",
                    black, white
                )
                remoteViews.setColorInt(
                    R.id.status_recording,
                    "setColorFilter",
                    if (state.recording.state) Color.RED else black,
                    if (state.recording.state) Color.RED else white
                )
                remoteViews.setFloat(
                    R.id.status_focus,
                    "setAlpha",
                    if (state.focus.state) 1.0f else 0.5f
                )
                remoteViews.setFloat(
                    R.id.status_shutter,
                    "setAlpha",
                    if (state.shutter.state) 1.0f else 0.5f
                )
                remoteViews.setFloat(
                    R.id.status_recording,
                    "setAlpha",
                    if (state.recording.state) 1.0f else 0.5f
                )
                buttonIDs.forEachIndexed { index, buttonID ->
                    customButtons?.getOrNull(index)?.also { cameraAction ->
                        val pressed = cameraAction.preset.template.referenceButton in state.pressedButtons || cameraAction.preset.template.referenceJog in state.pressedJogs
                        remoteViews.setImageViewBitmap(buttonID, getIconBmp(cameraAction, countDownTime == null, pressed))
                    }
                }
            } else {
                when (state) {
                    is CameraStateConnecting -> {
                        remoteViews.setTextViewText(
                            R.id.status_name,
                            context.getText(R.string.status_connecting)
                        )
                    }

                    is CameraStateGone -> {
                        remoteViews.setTextViewText(
                            R.id.status_name,
                            context.getText(R.string.status_offline)
                        )
                    }

                    is CameraStateError -> {
                        remoteViews.setTextViewText(
                            R.id.status_name,
                            context.getText(R.string.status_error).toString() + ": " + state.description
                        )
                    }

                    else -> {}
                }
                buttonIDs.forEachIndexed { index, buttonID ->
                    customButtons?.getOrNull(index)?.also { cameraAction ->
                        remoteViews.setImageViewBitmap(buttonID, getIconBmp(cameraAction,
                            available = false,
                            pressed = false
                        ))
                    }
                }
            }
        }

        countDownTime?.let { time ->
            remoteViews.setChronometer(R.id.status_countdown, time, null, true)
            remoteViews.setViewVisibility(R.id.status_countdown, View.VISIBLE)
        }

        countDownLabel?.let { label ->
            remoteViews.setTextViewText(R.id.status_action, label)
            remoteViews.setViewVisibility(R.id.status_action, View.VISIBLE)
        }

        return remoteViews
    }

    private fun setupNotificationBuilder(): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val remoteViews = createRemoteViews()

        return NotificationCompat.Builder(context, channelId)
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.ic_camera_black_24dp)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteViews)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
    }

    private fun getIconBmp(cameraAction: CameraAction, available: Boolean, pressed: Boolean): Bitmap {
        val defaultButtonSize =
            context.resources.getDimensionPixelSize(R.dimen.notification_default_button_size)

        val color = if (!available) {
            context.getColor(R.color.gray50)
        } else if (pressed) {
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
                context.getColor(R.color.cyan)
            } else {
                context.getColor(R.color.midnight_light)
            }
        } else {
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
                context.getColor(R.color.white)
            } else {
                context.getColor(R.color.black)
            }
        }

        val drawable = cameraAction.getIcon(context)
        drawable.setTint(color)
        return drawable.toBitmap((defaultButtonSize*buttonSize).roundToInt(), (defaultButtonSize*buttonSize).roundToInt())
    }

    fun updateCustomButtons(buttons: List<CameraAction>?, size: Float) {
        buttonSize = size
        customButtons = buttons
        updateNotification()
    }

    fun onCameraStateUpdate(state: CameraState) {
        cameraState = state
        updateNotification()
    }

    fun showCountdown(time: Long, label: String) {
        countDownTime = time
        countDownLabel = label
        updateNotification()
    }

    fun hideCountdown() {
        countDownTime = null
        countDownLabel = null
        updateNotification()
    }
}