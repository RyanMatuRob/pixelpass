package com.pixelpass.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pixelpass.ui.screens.SignInScreen
import com.pixelpass.ui.screens.QrGeneratorScreen
import com.pixelpass.ui.screens.ScannerStandbyScreen
import com.pixelpass.ui.screens.BiometricVerificationScreen

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
                        "qrGenerator/${Uri.encode(token)}"
                    }

                    navController.navigate(destination) {
                        popUpTo("signin") { inclusive = true }
                    }
                }
            )
        }

        composable("qrGenerator/{token}") { backStackEntry ->
            val rawToken = backStackEntry.arguments?.getString("token") ?: ""
            val token = Uri.decode(rawToken)
            QrGeneratorScreen(
                token = token,
                onBackClick = {
                    navController.navigate("signin") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onProfileClick = {
                    navController.navigate("signin") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("scanner_standby") {
            ScannerStandbyScreen(
                onQrScanned = { jwt ->
                    // Route to the biometric verification screen and pass the token
                    navController.navigate("biometric_verification/${Uri.encode(jwt)}")
                },
                onBackClick = {
                    navController.navigate("signin") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("biometric_verification/{jwt}") { backStackEntry ->
            val rawJwt = backStackEntry.arguments?.getString("jwt") ?: ""
            val jwt = Uri.decode(rawJwt)
            BiometricVerificationScreen(
                jwt = jwt,
                onVerificationComplete = {
                    // Route back to scanner after the visitor is processed
                    navController.popBackStack("scanner_standby", inclusive = false)
                }
            )
        }
    }
}
