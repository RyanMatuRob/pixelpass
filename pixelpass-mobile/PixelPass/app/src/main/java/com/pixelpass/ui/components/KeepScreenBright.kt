package com.pixelpass.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Forces the screen to stay lit and at full brightness while a QR code is on
 * screen, then restores whatever the user had when the pass closes. A dimmed or
 * sleeping screen is the most common reason a scanner cannot read a pass.
 */
@Composable
fun KeepScreenBright() {
    val view = LocalView.current

    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val attributes = window?.attributes
        val previousBrightness = attributes?.screenBrightness
        val previousKeepScreenOn = view.keepScreenOn

        view.keepScreenOn = true
        if (window != null && attributes != null) {
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            window.attributes = attributes
        }

        onDispose {
            view.keepScreenOn = previousKeepScreenOn
            if (window != null && attributes != null) {
                attributes.screenBrightness =
                    previousBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attributes
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
