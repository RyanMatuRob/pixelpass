package com.pixelpass.ui.screens
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pixelpass.network.LoginRequest
import com.pixelpass.network.RetrofitClient
import com.pixelpass.ui.theme.*

@Composable
fun SignInScreen(onLoginSuccess: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceLight)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Welcome to Nexus",
            style = AppTypography.headlineMedium
        ) //[cite: 5]

        Text(
            text = "Secure Access Platform",
            style = AppTypography.bodyLarge,
            color = Color.Gray
        ) //[cite: 5]

        Spacer(modifier = Modifier.height(32.dp))

        // Work Email Field[cite: 5]
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Work Email") },
            placeholder = { Text("alex@company.com") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password Field[cite: 5]
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            placeholder = { Text("At least 8 characters") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Sign In Button[cite: 5]
        Button(
            onClick = {
                if (email.isNotBlank() && password.isNotBlank()) {
                    isLoading = true
                    coroutineScope.launch {
                        try {
                            val request = LoginRequest(email, password)
                            val response = RetrofitClient.instance.login(request)

                            if (response.isSuccessful) {
                                val role = response.body()?.role
                                val token = response.body()?.token // Extract the JWT

                                Toast.makeText(context, "Welcome $role!", Toast.LENGTH_SHORT).show()

                                // Trigger the navigation callback and pass the token
                                if (token != null && role != null) {
                                    onLoginSuccess(token, role)
                                }
                            } else {
                                Toast.makeText(context, "Invalid credentials", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Network error", Toast.LENGTH_SHORT).show()
                        } finally {
                            isLoading = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            shape = RoundedCornerShape(12.dp),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "Signing in..." else "Sign In", color = SurfaceWhite)
        }
    }
}