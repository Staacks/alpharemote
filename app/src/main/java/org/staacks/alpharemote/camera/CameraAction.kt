package org.staacks.alpharemote.camera

import android.content.Context
import android.graphics.drawable.Drawable
import org.staacks.alpharemote.R
import java.io.Serializable
import kotlin.math.roundToInt

data class CameraAction (
    val toggle: Boolean,
    val selftimer: Float?,
    val duration: Float?,
    val step: Float?,
    val preset: CameraActionPreset
) : Serializable {
    fun getIcon(context: Context): Drawable {
        return CameraActionIcon(context, this)
    }
    fun getName(context: Context): String {
        return context.getString(preset.template.name) +
                (if (toggle) " " + context.getString(R.string.toggle) else "") +
                (if (selftimer != null) " timer=" + selftimer + "s" else "") +
                (if (duration != null) " duration=" + duration + "s" else "") +
                (if (step != null) " " + "›".repeat((3.0*step).roundToInt()) else "")
    }
    private fun applyStepToStepList(list: List<CameraActionStep>): List<CameraActionStep> {
        return list.map {
            if (it is CAJog && it.step == (-1).toByte()) {
                it.copy(step = (((step ?: 0.5f) * (it.jog.maxStep - it.jog.minStep)).roundToInt() + it.jog.minStep).toByte())
            } else {
                it
            }
        }
    }

    fun getPressStepList(context: Context): List<CameraActionStep> {
        return if (selftimer != null) {
            val label = context.getString(R.string.self_timer)
            listOf(CACountdown(label, selftimer)) + applyStepToStepList(preset.template.press)
        } else {
            applyStepToStepList(preset.template.press)
        }
    }

    fun getReleaseStepList(): List<CameraActionStep> {
        return applyStepToStepList(preset.template.release)
    }

    fun getClickStepList(context: Context): List<CameraActionStep> {
        return if (duration != null) {
            val label = context.getString(R.string.hold_button)
            getPressStepList(context) + listOf(CACountdown(label, duration)) + getReleaseStepList()
        } else {
            getPressStepList(context) + getReleaseStepList()
        }
    }
}

data class CameraActionTemplate (
    val name: Int,                                      // Resource ID for the name
    val icon: Int,                                      // Resource ID for the icon
    val preserveColor: Boolean = false,                 // Do not color in black and white (for example a red record button that should stay red)
    val press: List<CameraActionStep> = emptyList(),    // List of actions when the button is pressed
    val release: List<CameraActionStep> = emptyList(),  // List of actions when the button is released
    val userOptions: Set<CameraActionTemplateOption> = emptySet(), // Which options are available to the user for this template?
    val referenceButton: ButtonCode? = null,            // The state of this button determines whether the icon is shown as pressed and whether a toggle action is a press or release
    val referenceJog: JogCode? = null                   // Same as reference button, but for jogs
)

enum class CameraActionTemplateOption {
    VARIABLE_DURATION, //Duration is defined by button press duration and user may set a fixed duration
    SELFTIMER,         //User may add a selftimer
    TOGGLE,            //May be used as a toggle
    ADJUST_SPEED       //User may adjust speed of jog commands that have a speed set to -1
}

enum class CameraActionPreset(val template: CameraActionTemplate) {
    STOP(CameraActionTemplate(R.string.action_name_stop, R.drawable.ca_stop, false,
        listOf(), listOf(), setOf(), null
    )),
    SHUTTER_HALF(CameraActionTemplate(R.string.action_name_shutter_half, R.drawable.ca_shutter_half,
        press = listOf(CAButton(true, ButtonCode.SHUTTER_HALF)),
        release = listOf(CAButton(false, ButtonCode.SHUTTER_HALF)),
        userOptions = setOf(CameraActionTemplateOption.TOGGLE),
        referenceButton = ButtonCode.SHUTTER_HALF
    )),
    SHUTTER(CameraActionTemplate(R.string.action_name_shutter, R.drawable.ca_shutter,
        press = listOf(CAButton(true, ButtonCode.SHUTTER_HALF),
                CAButton(true, ButtonCode.SHUTTER_FULL)),
        release = listOf(CAButton(false, ButtonCode.SHUTTER_FULL),
                CAButton(false, ButtonCode.SHUTTER_HALF)),
        userOptions = setOf(CameraActionTemplateOption.VARIABLE_DURATION, CameraActionTemplateOption.SELFTIMER),
        referenceButton = ButtonCode.SHUTTER_FULL
    )),
    TRIGGER_ONCE(CameraActionTemplate(R.string.action_name_trigger_once, R.drawable.ca_trigger_once,
        press = listOf(CAButton(true, ButtonCode.SHUTTER_HALF),
                CAButton(true, ButtonCode.SHUTTER_FULL),
                CAWaitFor(WaitTarget.SHUTTER),
                CAButton(false, ButtonCode.SHUTTER_FULL),
                CAButton(false, ButtonCode.SHUTTER_HALF)),
        userOptions = setOf(CameraActionTemplateOption.SELFTIMER),
        referenceButton = ButtonCode.SHUTTER_FULL
    )),
    TRIGGER_ON_FOCUS(CameraActionTemplate(R.string.action_name_trigger_on_focus, R.drawable.ca_trigger_on_focus,
        press = listOf(CAButton(true, ButtonCode.SHUTTER_HALF),
            CAWaitFor(WaitTarget.FOCUS),
            CAButton(true, ButtonCode.SHUTTER_FULL)),
        release = listOf(CAButton(false, ButtonCode.SHUTTER_FULL),
            CAButton(false, ButtonCode.SHUTTER_HALF)),
        userOptions = setOf(CameraActionTemplateOption.VARIABLE_DURATION, CameraActionTemplateOption.SELFTIMER),
        referenceButton = ButtonCode.SHUTTER_FULL
    )),
    RECORD(CameraActionTemplate(R.string.action_name_record, R.drawable.ca_record, true,
        press = listOf(CAButton(true, ButtonCode.RECORD)),
        release = listOf(CAButton(false, ButtonCode.RECORD)),
        userOptions = setOf(CameraActionTemplateOption.SELFTIMER),
        referenceButton = ButtonCode.RECORD
    )),
    AF_ON(CameraActionTemplate(R.string.action_name_af_on, R.drawable.ca_af_on,
        press = listOf(CAButton(true, ButtonCode.AF_ON)),
        release = listOf(CAButton(false, ButtonCode.AF_ON)),
        userOptions = setOf(CameraActionTemplateOption.TOGGLE),
        referenceButton = ButtonCode.AF_ON
    )),
    C1(CameraActionTemplate(R.string.action_name_c1, R.drawable.ca_c1,
        press = listOf(CAButton(true, ButtonCode.C1)),
        release = listOf(CAButton(false, ButtonCode.C1)),
        referenceButton = ButtonCode.C1
    )),
    ZOOM_IN(CameraActionTemplate(R.string.action_name_zoom_in, R.drawable.ca_zoom_in,
        press = listOf(CAJog(true, -1, JogCode.ZOOM_IN)),
        release = listOf(CAJog(false, -1, JogCode.ZOOM_IN)),
        userOptions = setOf(CameraActionTemplateOption.VARIABLE_DURATION, CameraActionTemplateOption.ADJUST_SPEED),
        referenceJog = JogCode.ZOOM_IN
    )),
    ZOOM_OUT(CameraActionTemplate(R.string.action_name_zoom_out, R.drawable.ca_zoom_out,
        press = listOf(CAJog(true, -1, JogCode.ZOOM_OUT)),
        release = listOf(CAJog(false, -1, JogCode.ZOOM_OUT)),
        userOptions = setOf(CameraActionTemplateOption.VARIABLE_DURATION, CameraActionTemplateOption.ADJUST_SPEED),
        referenceJog = JogCode.ZOOM_OUT
    )),
    FOCUS_FAR(CameraActionTemplate(R.string.action_name_focus_far, R.drawable.ca_focus_far,
        press = listOf(CAJog(true, -1, JogCode.FOCUS_FAR)),
        release = listOf(CAJog(false, -1, JogCode.FOCUS_FAR)),
        userOptions = setOf(CameraActionTemplateOption.VARIABLE_DURATION, CameraActionTemplateOption.ADJUST_SPEED),
        referenceJog = JogCode.FOCUS_FAR
    )),
    FOCUS_NEAR(CameraActionTemplate(R.string.action_name_focus_near, R.drawable.ca_focus_near,
        press = listOf(CAJog(true, -1, JogCode.FOCUS_NEAR)),
        release = listOf(CAJog(false, -1, JogCode.FOCUS_NEAR)),
        userOptions = setOf(CameraActionTemplateOption.VARIABLE_DURATION, CameraActionTemplateOption.ADJUST_SPEED),
        referenceJog = JogCode.FOCUS_NEAR
    ))
}