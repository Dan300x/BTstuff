/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.platform.media.ultrahdr.common

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.Window
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import com.example.platform.media.ultrahdr.R
import com.example.platform.media.ultrahdr.databinding.ColorModeControlsBinding
import java.util.function.Consumer

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class ColorModeControls : LinearLayout, WindowObserver {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    /**
     *  Android ViewBinding.
     */
    private var _binding: ColorModeControlsBinding? = null
    private val binding get() = _binding!!

    /**
     * Reference to [Window]. This should come from the currently active activity.
     */
    private var window: Window? = null

    init { // Inflate binding
        _binding = ColorModeControlsBinding.inflate(LayoutInflater.from(context), this, true)
    }

    private val hdrSdrRatioListener = Consumer<Display> { display ->
        Log.d(TAG, "HDR/SDR Ratio Changed ${display.hdrSdrRatio}")
        post { updateModeInfoDisplay() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        display?.run {
            registerHdrSdrRatioChangedListener({ it.run() }, hdrSdrRatioListener)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        display?.run {
            display.unregisterHdrSdrRatioChangedListener(hdrSdrRatioListener)
        }
    }

    override fun setWindow(window: Window?) {
        this.window = window
        this.window?.let {
            setColorMode(it.colorMode)

            binding.ultrahdrColorModeSrgb.setOnClickListener {
                setColorMode(ActivityInfo.COLOR_MODE_DEFAULT)
            }
            
            binding.ultrahdrColorModeHdr.setOnClickListener {
                setColorMode(ActivityInfo.COLOR_MODE_HDR)
            }
        }
    }

    private fun updateModeInfoDisplay() {
        window?.let {
            // The current hdr/sdr ratio expressed as the ratio of
            // targetHdrPeakBrightnessInNits / targetSdrWhitePointInNits.
            val sdrHdrRatio = when (display.isHdrSdrRatioAvailable) {
                true -> display.hdrSdrRatio
                false -> 1.0f
            }

            binding.ultrahdrColorModeCurrentMode.run {
                val mode = when (it.colorMode) {
                    ActivityInfo.COLOR_MODE_DEFAULT -> resources.getString(R.string.ultrahdr_color_mode_srgb)
                    ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT -> resources.getString(R.string.ultrahdr_color_mode_p3)
                    ActivityInfo.COLOR_MODE_HDR -> String.format(
                        resources.getString(R.string.ultrahdr_color_mode_hdr_with_ratio),
                        sdrHdrRatio,
                    )

                    else -> resources.getString(R.string.ultrahdr_color_mode_unknown)
                }
                text = "Activity Color Mode: " + mode
            }
        }
    }

    /**
     * Set the [ActivityInfo] Color Mode to the Window. Setting this to [ActivityInfo.COLOR_MODE_HDR]
     * on a supported device will turn on the high brightness mode that is required to view and
     * UltraHDR image properly.
     */
    private fun setColorMode(newMode: Int) = window?.let {
        it.colorMode = newMode
        updateModeInfoDisplay()
    }

    companion object {
        private val TAG = ColorModeControls::class.java.simpleName
    }
}