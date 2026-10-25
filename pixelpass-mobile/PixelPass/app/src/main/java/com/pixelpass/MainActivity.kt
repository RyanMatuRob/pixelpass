package com.pixelpass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pixelpass.ui.session.PixelPassRoot
import com.pixelpass.ui.theme.PixelPassTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Every launch starts at the session gate: restore the stored session,
        // then either render the pass/scanner or fall back to sign-in.
        setContent {
            PixelPassTheme {
                PixelPassRoot()
            }
        }
    }
}
