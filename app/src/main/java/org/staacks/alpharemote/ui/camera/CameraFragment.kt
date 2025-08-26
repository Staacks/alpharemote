package org.staacks.alpharemote.ui.camera

import android.animation.LayoutTransition
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.staacks.alpharemote.MainActivity
import org.staacks.alpharemote.R
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.ButtonCode
import org.staacks.alpharemote.camera.CAButton
import org.staacks.alpharemote.camera.CACountdown
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset
import org.staacks.alpharemote.camera.CameraActionStep
import org.staacks.alpharemote.camera.CameraState
import org.staacks.alpharemote.camera.CameraStateReady
import org.staacks.alpharemote.databinding.FragmentCameraBinding
import org.staacks.alpharemote.service.AlphaRemoteService
import org.staacks.alpharemote.service.ServiceRunning
import org.staacks.alpharemote.ui.help.HelpDialogFragment
import java.io.Serializable
import kotlin.concurrent.timer


class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private var cameraViewModel: CameraViewModel? = null

    private var customButtons: List<CameraAction>? = null

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        cameraViewModel = ViewModelProvider(this)[CameraViewModel::class.java]

        viewLifecycleOwner.lifecycleScope.launch {
            cameraViewModel?.uiAction?.collect{ action ->
                when (action) {
                    is CameraViewModel.GenericCameraUIAction -> {
                        when (action.action) {
                            CameraViewModel.GenericCameraUIActionType.GOTO_DEVICE_SETTINGS -> gotoDeviceSettings()
                            CameraViewModel.GenericCameraUIActionType.HELP_REMOTE ->
                                HelpDialogFragment().setContent(
                                    R.string.help_camera_remote_title,
                                    R.string.help_camera_remote_text
                                ).show(childFragmentManager, null)
                            CameraViewModel.GenericCameraUIActionType.START_ADVANCED_SEQUENCE -> startAdvancedSequence()
                        }
                    }
                    is CameraViewModel.DefaultRemoteButtonCameraUIAction -> {
                        when (action.button) {
                            DefaultRemoteButton.Button.SHUTTER ->
                                sendCameraActionToService(CameraAction(false, null, null, null, CameraActionPreset.SHUTTER), action.event)
                            DefaultRemoteButton.Button.SHUTTER_HALF ->
                                sendCameraActionToService(CameraAction(true, null, null, null, CameraActionPreset.SHUTTER_HALF), action.event)
                            DefaultRemoteButton.Button.SELFTIMER_3S -> {
                                if (action.event == MotionEvent.ACTION_UP) {
                                    sendCameraActionToService(CameraAction(false,3.0f,null,null,CameraActionPreset.TRIGGER_ONCE), null)
                                }
                            }
                            DefaultRemoteButton.Button.RECORD ->
                                sendCameraActionToService(CameraAction(false, null, null, null, CameraActionPreset.RECORD), action.event)
                            DefaultRemoteButton.Button.C1 ->
                                sendCameraActionToService(CameraAction(false, null, null, null, CameraActionPreset.C1), action.event)
                            DefaultRemoteButton.Button.AF_ON ->
                                sendCameraActionToService(CameraAction(true, null, null, null, CameraActionPreset.AF_ON), action.event)
                            DefaultRemoteButton.Button.ZOOM_IN ->
                                sendCameraActionToService(CameraAction(false, null, null, null, CameraActionPreset.ZOOM_IN), action.event)
                            DefaultRemoteButton.Button.ZOOM_OUT ->
                                sendCameraActionToService(CameraAction(false, null, null, null, CameraActionPreset.ZOOM_OUT), action.event)
                            DefaultRemoteButton.Button.FOCUS_FAR ->
                                sendCameraActionToService(CameraAction(false, null, null, null, CameraActionPreset.FOCUS_FAR), action.event)
                            DefaultRemoteButton.Button.FOCUS_NEAR ->
                                sendCameraActionToService(CameraAction(false, null, null, null, CameraActionPreset.FOCUS_NEAR), action.event)
                        }
                    }
                }
            }
        }

        val settingsStore = SettingsStore(requireContext())

        _binding = DataBindingUtil.inflate(inflater, R.layout.fragment_camera, container, false)
        val binding = _binding!!
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = cameraViewModel
        binding.defaultRemote.lifecycleOwner = viewLifecycleOwner
        binding.defaultRemote.viewModel = cameraViewModel
        binding.advancedControls.lifecycleOwner = viewLifecycleOwner
        binding.advancedControls.viewModel = cameraViewModel

        viewLifecycleOwner.lifecycleScope.launch {
            cameraViewModel?.uiState?.collect{ state ->
                state.serviceState?.countdown?.let {
                    binding.statusCountdown.base = it
                    binding.statusCountdown.start()
                }
                state.cameraState?.recording?.let {
                    if (it.state) {
                        it.lastChange?.let { time ->
                            binding.statusRecordingTime.base = time
                            binding.statusRecordingTime.start()
                        }
                    } else {
                        binding.statusRecordingTime.stop()
                    }
                }
                state.cameraState?.let {
                    binding.defaultRemote.buttonAfOn.updateCameraState(it)
                    binding.defaultRemote.buttonC1.updateCameraState(it)
                    binding.defaultRemote.buttonFocusFar.updateCameraState(it)
                    binding.defaultRemote.buttonFocusNear.updateCameraState(it)
                    binding.defaultRemote.buttonRecord.updateCameraState(it)
                    binding.defaultRemote.buttonSelftimer3s.updateCameraState(it)
                    binding.defaultRemote.buttonShutter.updateCameraState(it)
                    binding.defaultRemote.buttonShutterHalf.updateCameraState(it)
                    binding.defaultRemote.buttonZoomIn.updateCameraState(it)
                    binding.defaultRemote.buttonZoomOut.updateCameraState(it)
                    updateCustomButtonsState(it)
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.constraintLayoutInset) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = 0,
            )
            WindowInsetsCompat.CONSUMED
        }

        viewLifecycleOwner.lifecycleScope.launch {
            settingsStore.customButtonSettings.stateIn(
                scope = this,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SettingsStore.CustomButtonSettings(null, 1.0f)
            ).collectLatest{
                customButtons = it.customButtonList
                updateCustomButtons(it.customButtonList)
            }
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun sendCameraActionToService(cameraAction: CameraAction, event: Int?) {
        if (AlphaRemoteService.serviceState.value is ServiceRunning) {
            val intent = Intent(context, AlphaRemoteService::class.java).apply {
                action = AlphaRemoteService.BUTTON_INTENT_ACTION
                putExtra(AlphaRemoteService.BUTTON_INTENT_CAMERA_ACTION_EXTRA, cameraAction as Serializable)
                event?.let {
                    if (event == MotionEvent.ACTION_DOWN)
                        putExtra(AlphaRemoteService.BUTTON_INTENT_CAMERA_ACTION_UP_EXTRA, false)
                    else
                        putExtra(AlphaRemoteService.BUTTON_INTENT_CAMERA_ACTION_DOWN_EXTRA, false)
                }
            }
            context?.startService(intent)
        }
    }

    private fun updateCustomButtons(buttons: List<CameraAction>?) {
        //We basically just recreate the NotificationUI, but do not have to use remoteViews.

        _binding?.let { binding ->
            binding.advancedControls.customButtons.removeAllViews()
            val context = binding.advancedControls.customButtons.context
            val parentID = binding.advancedControls.customButtons.id
            val unset = ConstraintLayout.LayoutParams.UNSET
            var previousID = -1
            var nextID = -1

            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple,true)

            val colorAttr = TypedValue()
            context.theme.resolveAttribute(R.attr.colorCustomButton, colorAttr,true)
            context.theme.resolveAttribute(R.attr.colorCustomButton, colorAttr,true)
            val color = context.getColor(colorAttr.resourceId)

            buttons?.let {
                val n = it.size
                it.forEachIndexed { i, cameraAction ->
                    val imageView = ImageView(context)
                    imageView.setImageDrawable(cameraAction.getIcon(context))
                    imageView.setBackgroundResource(ripple.resourceId)
                    imageView.imageTintList = ColorStateList.valueOf(color)

                    imageView.isClickable = true
                    imageView.setOnClickListener {
                        sendCameraActionToService(cameraAction, null)
                    }

                    imageView.id = if (i == 0) View.generateViewId() else nextID
                    if (i != n-1)
                        nextID = View.generateViewId()
                    imageView.layoutParams = ConstraintLayout.LayoutParams(0, 0)
                    imageView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                        startToStart = if (i == 0) parentID else unset
                        startToEnd = if (i == 0) unset else previousID
                        endToEnd = if (i == n-1) parentID else unset
                        endToStart = if (i == n-1) unset else nextID
                        topToTop = parentID
                        bottomToBottom = parentID
                        dimensionRatio = "1:1"
                    }
                    previousID = imageView.id

                    binding.advancedControls.customButtons.addView(imageView)
                }
            }
        }
    }

    private fun updateCustomButtonsState(cameraState: CameraState?) {
        _binding?.let { binding ->
            context?.let { context ->
                customButtons?.let {
                    val colorAttr = TypedValue()
                    context.theme.resolveAttribute(R.attr.colorCustomButton, colorAttr, true)
                    val baseColor = context.getColor(colorAttr.resourceId)
                    context.theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, colorAttr, true)
                    val pressedColor = context.getColor(colorAttr.resourceId)
                    val disabledColor = context.getColor(R.color.gray50)

                    it.forEachIndexed { i, cameraAction ->
                        if (!cameraAction.preset.template.preserveColor) {
                            val color = if (cameraState is CameraStateReady) {
                                if (cameraAction.preset.template.referenceButton in cameraState.pressedButtons || cameraAction.preset.template.referenceJog in cameraState.pressedJogs)
                                    pressedColor
                                else
                                    baseColor
                            } else {
                                disabledColor
                            }
                            (binding.advancedControls.customButtons.getChildAt(i) as? ImageView)?.let{ v ->
                                v.imageTintList = ColorStateList.valueOf(color)
                                v.invalidate()
                            }
                        }
                    }
                    binding.advancedControls.customButtons.invalidate()
                }
            }
        }
    }

    private fun gotoDeviceSettings() {
        (activity as MainActivity).navigateTo(R.id.navigation_settings)
    }

    private fun startAdvancedSequence() {
        if (AlphaRemoteService.serviceState.value !is ServiceRunning)
            return

        cameraViewModel?.uiState?.value?.let { uiState ->
            val bulbDuration = if (uiState.bulbToggle.get() == true) {uiState.bulbDuration ?: 0.0} else {0.0}
            val intervalCount = if (uiState.intervalToggle.get() == true) {uiState.intervalCount ?: 1} else {1}
            val intervalDuration = if (uiState.intervalToggle.get() == true) {uiState.intervalDuration ?: 0.0} else {0.0}

            val intent = Intent(context, AlphaRemoteService::class.java).apply {
                action = AlphaRemoteService.ADVANCED_SEQUENCE_INTENT_ACTION
                putExtra(AlphaRemoteService.ADVANCED_SEQUENCE_INTENT_BULB_DURATION_EXTRA, bulbDuration.toFloat())
                putExtra(AlphaRemoteService.ADVANCED_SEQUENCE_INTENT_INTERVAL_COUNT_EXTRA, intervalCount)
                putExtra(AlphaRemoteService.ADVANCED_SEQUENCE_INTENT_INTERVAL_DURATION_EXTRA, intervalDuration.toFloat())
            }

            context?.startService(intent)
        }
    }


}