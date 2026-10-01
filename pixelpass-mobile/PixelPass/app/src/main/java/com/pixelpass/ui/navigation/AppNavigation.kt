package com.pixelpass.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pixelpass.ui.screens.SignInScreen
import com.pixelpass.ui.screens.QrGeneratorScreen
import com.pixelpass.ui.screens.ScannerStandbyScreen

@Composable
fun PixelPassNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "signin") {
        composable("signin") {
            SignInScreen(
                onLoginSuccess = { token, role ->
                    // Route SECURITY personnel to the camera, everyone else to their QR code
                    val destination = if (role == "SECURITY") {
                        "scanner_standby"
                    } else {
                        "qr_generator/$token"
                    }

                    navController.navigate(destination) {
                        popUpTo("signin") { inclusive = true }
                    }
                }
            )
        }

        composable("qr_generator/{token}") { backStackEntry ->
            val token = backStackEntry.arguments?.getString("token") ?: ""
            QrGeneratorScreen(token = token)
        }

        composable("scanner_standby") {
            ScannerStandbyScreen()
        }
    }
}