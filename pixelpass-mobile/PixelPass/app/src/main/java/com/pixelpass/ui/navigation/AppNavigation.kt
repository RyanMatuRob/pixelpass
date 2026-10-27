package com.pixelpass.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pixelpass.data.session.UserSession
import com.pixelpass.scan.ScanIntake
import com.pixelpass.scan.ScanSource
import com.pixelpass.scan.ScanVerdict
import com.pixelpass.scan.toVerdict
import com.pixelpass.ui.screens.BiometricVerificationScreen
import com.pixelpass.ui.screens.QrGeneratorScreen
import com.pixelpass.ui.screens.ScanVerdictScreen
import com.pixelpass.ui.screens.ScannerStandbyScreen

private const val ROUTE_QR_GENERATOR = "qrGenerator"
private const val ROUTE_SCANNER = "scanner_standby"
private const val ROUTE_BIOMETRIC = "biometric_verification/{jwt}?nonce={nonce}&signed={signed}"
private const val ROUTE_VERDICT = "scan_verdict/{verdict}?detail={detail}&queued={queued}"

/**
 * Signed-in navigation. The start destination comes from the signed-in role, so
 * a guard lands on the scanner and everyone else lands on their pass.
 *
 * Every scan ends on [ROUTE_VERDICT], which owns the whole screen: a guard never
 * has to hunt for the answer while a person is waiting at the gate.
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
                onQrScanned = { accepted -> navController.navigate(accepted.toBiometricRoute()) },
                onBackClick = onSignOut,
                onReplayDetected = { reason ->
                    navController.navigate(
                        ScanVerdict.REPLAY.toRoute(detail = reason, queuedForSync = false)
                    )
                }
            )
        }

        composable(
            route = ROUTE_BIOMETRIC,
            arguments = listOf(
                navArgument("jwt") { type = NavType.StringType },
                navArgument("nonce") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("signed") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val jwt = Uri.decode(backStackEntry.arguments?.getString("jwt").orEmpty())
            val nonce = Uri.decode(backStackEntry.arguments?.getString("nonce").orEmpty())
            BiometricVerificationScreen(
                jwt = jwt,
                nonce = nonce.ifBlank { null },
                signed = backStackEntry.arguments?.getBoolean("signed") == true,
                onOutcome = { outcome ->
                    navController.navigate(
                        outcome.toVerdict().toRoute(
                            detail = outcome.detail,
                            queuedForSync = outcome.queuedForSync
                        )
                    ) {
                        // The face-capture step is done; only the verdict and the
                        // scanner survive so Back never replays a spent pass.
                        popUpTo(backStackEntry.destination.id) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = ROUTE_VERDICT,
            arguments = listOf(
                navArgument("verdict") { type = NavType.StringType },
                navArgument("detail") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("queued") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val verdictName = backStackEntry.arguments?.getString("verdict").orEmpty()
            ScanVerdictScreen(
                verdict = ScanVerdict.entries.firstOrNull { it.name == verdictName }
                    ?: ScanVerdict.DENIED,
                detail = Uri.decode(backStackEntry.arguments?.getString("detail").orEmpty()),
                queuedForSync = backStackEntry.arguments?.getBoolean("queued") == true,
                onScanNext = { navController.popBackStack(ROUTE_SCANNER, inclusive = false) }
            )
        }
    }
}

/** Carries the accepted pass, and how it was verified, into the face-capture step. */
private fun ScanIntake.Accepted.toBiometricRoute(): String {
    val signed = source == ScanSource.SIGNED_PASS
    return "biometric_verification/${Uri.encode(jwt)}" +
        "?nonce=${Uri.encode(nonce.orEmpty())}&signed=$signed"
}

/** Carries one full-screen verdict, with the sentence the gateway or device produced. */
private fun ScanVerdict.toRoute(detail: String, queuedForSync: Boolean): String =
    "scan_verdict/$name?detail=${Uri.encode(detail)}&queued=$queuedForSync"

