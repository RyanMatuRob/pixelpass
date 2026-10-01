package com.pixelpass // We will fix this below

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pixelpass.ui.navigation.PixelPassNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // setContent defines the activity's layout where composable functions are called.
        setContent {
            // Note: If you created a custom Theme class in ui/theme, wrap this screen inside it.
            PixelPassNavigation()
        }
    }
}