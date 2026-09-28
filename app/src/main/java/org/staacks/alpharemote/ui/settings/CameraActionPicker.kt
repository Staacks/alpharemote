package org.staacks.alpharemote.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import org.staacks.alpharemote.R
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset
import org.staacks.alpharemote.camera.CameraActionTemplateOption
import org.staacks.alpharemote.databinding.CameraActionPickerBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

interface CameraActionPickerListener {
    fun onConfirmCameraActionPicker(index: Int, cameraAction: CameraAction)
    fun onCancelCameraActionPicker()
    fun onDeleteCameraActionPicker(index: Int)
}

class CameraActionPicker : DialogFragment() {

    private var _binding: CameraActionPickerBinding? = null
    private val binding get() = _binding!!

    private var index = -1
    private var capturingKey = false

    val defaultAction = CameraAction(
        false, null, null, null, CameraActionPreset.STOP
    )

    private lateinit var cameraAction: MutableStateFlow<CameraAction>

    class SeekBarTimeMap(min: Int, max: Int) {

        private val mapping = generateSequence(min) {
            if (it < 10)
                it + 1
            else if (it < 50)
                it + 5
            else if (it < 300)
                it + 10
            else if (it < 600)
                it + 50
            else
                it + 100
        }.takeWhile { it <= max }.toList()

        fun getMax(): Int {
            return mapping.count() - 1
        }

        fun indexToTime(i: Int): Float {
            return mapping[i] / 10.0f
        }

        fun timeToIndex(t: Float): Int {
            return mapping.indexOf((t * 10.0f).roundToInt())
        }
    }

    val selftimerSeekBarTimeMap = SeekBarTimeMap(10, 600)
    val holdSeekBarTimeMap = SeekBarTimeMap(0, 100)

    companion object {
        const val CAMERA_ACTION_KEY = "cameraAction"
        const val INDEX_KEY = "index"
        const val SHOW_DELETE_KEY = "showDelete"
        fun newInstance(index: Int, cameraAction: CameraAction?, showDelete: Boolean): CameraActionPicker {
            val newInstance = CameraActionPicker()
            val args = Bundle()
            args.putSerializable(CAMERA_ACTION_KEY, cameraAction)
            args.putInt(INDEX_KEY, index)
            args.putBoolean(SHOW_DELETE_KEY, showDelete)
            newInstance.setArguments(args)
            return newInstance
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = CameraActionPickerBinding.inflate(layoutInflater)

        index = arguments?.getInt(INDEX_KEY) ?: -1
        val oldAction = arguments?.getSerializable(CAMERA_ACTION_KEY) as? CameraAction
        val startAction = oldAction ?: defaultAction

        val showDelete = arguments?.getBoolean(SHOW_DELETE_KEY) ?: false

        cameraAction = MutableStateFlow(startAction)

        val actionSpinnerAdapter = ArrayAdapter(
            requireActivity(),
            android.R.layout.simple_spinner_item,
            CameraActionPreset.entries.map { getString(it.template.name) }
        ).also{
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.capAction.adapter = actionSpinnerAdapter
        binding.capAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val preset = CameraActionPreset.entries[position]
                lifecycleScope.launch {
                    val old = cameraAction.value
                    val opt = preset.template.userOptions
                    cameraAction.emit(old.copy(
                        preset = preset,
                        selftimer = if (opt.contains(CameraActionTemplateOption.SELFTIMER)) old.selftimer else null,
                        duration = if (opt.contains(CameraActionTemplateOption.VARIABLE_DURATION)) old.duration else null,
                        toggle = if (opt.contains(CameraActionTemplateOption.TOGGLE)) old.toggle else false,
                        step = if (opt.contains(CameraActionTemplateOption.ADJUST_SPEED)) old.step ?: 0.5f else null
                    ))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                lifecycleScope.launch {
                    cameraAction.emit(defaultAction)
                }
            }

        }

        binding.capSelftimerEnable.setOnCheckedChangeListener { buttonView, isChecked ->
            lifecycleScope.launch {
                cameraAction.emit(cameraAction.value.copy(
                    selftimer = if (isChecked) (selftimerSeekBarTimeMap.indexToTime(binding.capHold.progress)) else null
                ))
            }
        }
        binding.capSelftimer.max = selftimerSeekBarTimeMap.getMax()
        binding.capSelftimer.setOnSeekBarChangeListener(object: OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lifecycleScope.launch {
                    cameraAction.emit(
                        cameraAction.value.copy(
                            selftimer = selftimerSeekBarTimeMap.indexToTime(progress)
                        )
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }

        })

        binding.capHoldEnable.setOnCheckedChangeListener { buttonView, isChecked ->
            lifecycleScope.launch {
                cameraAction.emit(cameraAction.value.copy(
                    duration = if (isChecked) (holdSeekBarTimeMap.indexToTime(binding.capHold.progress)) else null
                ))
            }
        }
        binding.capHold.max = holdSeekBarTimeMap.getMax()
        binding.capHold.setOnSeekBarChangeListener(object: OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lifecycleScope.launch {
                    cameraAction.emit(
                        cameraAction.value.copy(
                            duration = holdSeekBarTimeMap.indexToTime(progress)
                        )
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }

        })

        binding.capToggle.setOnCheckedChangeListener { buttonView, isChecked ->
            lifecycleScope.launch {
                cameraAction.emit(cameraAction.value.copy(
                    toggle = isChecked
                ))
            }
        }

        binding.capSpeed.setOnSeekBarChangeListener(object: OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lifecycleScope.launch {
                    cameraAction.emit(
                        cameraAction.value.copy(
                            step = progress / 100.0f
                        )
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }

        })

        binding.capKeyBind.setOnClickListener {
            setCapturingKey(true)
        }

        binding.capKeyClear.setOnClickListener {
            lifecycleScope.launch {
                cameraAction.emit(cameraAction.value.copy(keyCode = null))
            }
        }

        binding.capCancel.setOnClickListener{
            (parentFragment as? CameraActionPickerListener)?.onCancelCameraActionPicker()
            dismiss()
        }
        binding.capSave.setOnClickListener{
            val action = cameraAction.value
            val options = action.preset.template.userOptions
            val prunedAction = action.copy(
                selftimer = if (options.contains(CameraActionTemplateOption.SELFTIMER)) action.selftimer else null,
                duration = if (options.contains(CameraActionTemplateOption.VARIABLE_DURATION)) action.duration else null,
                toggle = options.contains(CameraActionTemplateOption.TOGGLE) && action.toggle,
                step = if (options.contains(CameraActionTemplateOption.ADJUST_SPEED)) action.step else null,
                keyCode = action.keyCode
            )
            (parentFragment as? CameraActionPickerListener)?.onConfirmCameraActionPicker(
                index, prunedAction
            )
            dismiss()
        }
        if (showDelete) {
            binding.capDelete.setOnClickListener {
                (parentFragment as? CameraActionPickerListener)?.onDeleteCameraActionPicker(index)
                dismiss()
            }
            binding.capDelete.visibility = VISIBLE
        } else
            binding.capDelete.visibility = GONE

        lifecycleScope.launch {
            cameraAction.collect{
                binding.capIcon.setImageDrawable(it.getIcon(requireContext()))
                binding.capTitle.text = it.getName(requireContext())

                binding.capSelftimerGroup.visibility = if (it.preset.template.userOptions.contains(CameraActionTemplateOption.SELFTIMER)) VISIBLE else GONE
                binding.capHoldGroup.visibility = if (it.preset.template.userOptions.contains(CameraActionTemplateOption.VARIABLE_DURATION)) VISIBLE else GONE
                binding.capToggle.visibility = if (it.preset.template.userOptions.contains(CameraActionTemplateOption.TOGGLE)) VISIBLE else GONE
                binding.capSpeedGroup.visibility = if (it.preset.template.userOptions.contains(CameraActionTemplateOption.ADJUST_SPEED)) VISIBLE else GONE

                binding.capAction.setSelection(it.preset.ordinal)

                binding.capSelftimerEnable.isChecked = (it.selftimer != null)
                if (binding.capSelftimerEnable.isChecked) {
                    binding.capSelftimer.alpha = 1.0f
                    binding.capSelftimer.progress = selftimerSeekBarTimeMap.timeToIndex (it.selftimer ?: 3.0f)
                    binding.capSelftimerSeconds.text = String.format(getString(R.string.seconds_formatted),it.selftimer ?: 3.0f)
                } else {
                    binding.capSelftimer.alpha = 0.5f
                    binding.capSelftimerSeconds.text = "-"
                }
                binding.capHoldEnable.isChecked = (it.duration != null)
                if (binding.capHoldEnable.isChecked) {
                    binding.capHold.alpha = 1.0f
                    binding.capHold.progress = holdSeekBarTimeMap.timeToIndex (it.duration ?: 3.0f)
                    binding.capHoldSeconds.text = String.format(getString(R.string.seconds_formatted),it.duration ?: 3.0f)
                } else {
                    binding.capHold.alpha = 0.5f
                    binding.capHoldSeconds.text = "-"
                }
                binding.capToggle.isChecked = it.toggle
                it.step?.let { step ->
                    binding.capSpeed.progress = (step * 100f).roundToInt()
                }
                binding.capKeyClear.visibility = if (it.keyCode != null) VISIBLE else GONE
            }
        }

        return AlertDialog.Builder(requireActivity()).setView(binding.root).create().apply {
            setOnKeyListener { _, keyCode, event ->
                if (!capturingKey || KeyEvent.isModifierKey(keyCode) || keyCode == KeyEvent.KEYCODE_UNKNOWN)
                    return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) {
                    setCapturingKey(false)
                    if (keyCode != KeyEvent.KEYCODE_BACK) {
                        lifecycleScope.launch {
                            cameraAction.emit(cameraAction.value.copy(keyCode = keyCode))
                        }
                    }
                }
                true
            }
        }
    }

    private fun setCapturingKey(capturing: Boolean) {
        capturingKey = capturing
        binding.capKeyBind.setText(if (capturing) R.string.key_binding_press_key else R.string.key_binding_bind)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        (parentFragment as? CameraActionPickerListener)?.onCancelCameraActionPicker()
    }
}