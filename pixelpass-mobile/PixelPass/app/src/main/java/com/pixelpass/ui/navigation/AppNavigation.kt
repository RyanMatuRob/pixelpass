package com.pixelpass.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pixelpass.data.session.UserSession
import com.pixelpass.ui.screens.BiometricVerificationScreen
import com.pixelpass.ui.screens.QrGeneratorScreen
import com.pixelpass.ui.screens.ScannerStandbyScreen

private const val ROUTE_QR_GENERATOR = "qrGenerator"
private const val ROUTE_SCANNER = "scanner_standby"
private const val ROUTE_BIOMETRIC = "biometric_verification/{jwt}"

/**
 * Signed-in navigation. The start destination comes from the signed-in role, so
 * a guard lands on the scanner and everyone else lands on their pass.
 */
@Composable
fun PixelPassNavigation(session: UserSession, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val startDestination = if (session.isGuard) ROUTE_SCANNER else ROUTE_QR_GENERATOR

    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_QR_GENERATOR) {
            QrGeneratorScreen(
                session = session,
                onBackClick = onSignOut,
                onProfileClick = onSignOut
            )
        }

        composable(ROUTE_SCANNER) {
            ScannerStandbyScreen(
                onQrScanned = { jwt ->
                    navController.navigate("biometric_verification/${Uri.encode(jwt)}")
                },
                onBackClick = onSignOut
            )
        }

        composable(ROUTE_BIOMETRIC) { backStackEntry ->
            val jwt = Uri.decode(backStackEntry.arguments?.getString("jwt") ?: "")
            BiometricVerificationScreen(
                jwt = jwt,
                onVerificationComplete = {
                    navController.popBackStack(ROUTE_SCANNER, inclusive = false)
                }
            )
        }
    }
}
