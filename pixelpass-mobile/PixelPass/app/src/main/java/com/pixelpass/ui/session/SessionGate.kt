package com.pixelpass.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixelpass.ui.navigation.PixelPassNavigation
import com.pixelpass.ui.screens.SignInScreen

/**
 * Decides the first screen of every launch: restore the session, then either go
 * straight to the pass/scanner or show sign-in. No screen decides this itself.
 */
@Composable
fun PixelPassRoot(sessionViewModel: SessionViewModel = viewModel()) {
    val state by sessionViewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        SessionState.Loading -> SessionLoadingScreen()

        SessionState.SignedOut -> SignInScreen(
            onLoginSuccess = { token, role, keepSignedIn ->
                sessionViewModel.signIn(token, role, keepSignedIn)
            }
        )

        is SessionState.SignedIn -> PixelPassNavigation(
            session = current.session,
            onSignOut = sessionViewModel::signOut
        )
    }
}

@Composable
private fun SessionLoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("PixelPass", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2DE0))
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = Color(0xFF3D2DE0),
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Restoring secure session", fontSize = 13.sp, color = Color(0xFF6B7280))
    }
}
