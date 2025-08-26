package org.staacks.alpharemote.ui

import android.util.Log
import android.widget.TextView
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter

@BindingAdapter("android:text")
fun setTextFromDouble(view: TextView, value: Double?) {
    value?.let {
        if (!it.toString().contentEquals(view.text))
            view.text = it.toString()
    }
}

@BindingAdapter("android:text")
fun setTextFromInteger(view: TextView, value: Int?) {
    value?.let {
        if (!it.toString().contentEquals(view.text))
            view.text = it.toString()
    }
}

@InverseBindingAdapter(attribute = "android:text")
fun getIntFromText(view: TextView): Int? {
    return try {
        view.text.toString().toInt()
    } catch (e: NumberFormatException) {
        null
    }
}

@InverseBindingAdapter(attribute = "android:text")
fun getDoubleFromText(view: TextView): Double? {
    return try {
        view.text.toString().toDouble()
    } catch (e: NumberFormatException) {
        null
    }
}