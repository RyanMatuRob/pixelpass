package com.pixelpass.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelpass.network.LoginRequest
import com.pixelpass.network.RetrofitClient
import kotlinx.coroutines.launch

// Exact Colors extracted from the mockup
private val BrandPurple = Color(0xFF3D2DE0)
private val BrandBg = Color(0xFFF8F9FA)
private val InputBg = Color(0xFFF3F4F6)
private val TextDark = Color(0xFF1A1F36)
private val TextGray = Color(0xFF6B7280)

@Composable
fun SignInScreen(onLoginSuccess: (String, String) -> Unit) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // UI State Toggles
    var showPassword by remember { mutableStateOf(false) }
    var isPhoneMode by remember { mutableStateOf(false) }
    var keepSignedIn by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandBg)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // 1. Top Logo Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrandPurple),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Shield Logo",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextDark)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 2. Main Floating Card
        Card(
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Drag Indicator Pill
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFE5E7EB))
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text("Welcome to Nexus", fontWeight = FontWeight.Bold, fontSize = 26.sp, color = TextDark)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Secure Access Platform", fontSize = 14.sp, color = TextGray)

                Spacer(modifier = Modifier.height(32.dp))

                // 3. Identifier Input (Email/Phone Toggle)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (isPhoneMode) "Phone Number" else "Work Email",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )
                    Text(
                        text = if (isPhoneMode) "Use email instead" else "Use phone instead",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandPurple,
                        modifier = Modifier.clickable { isPhoneMode = !isPhoneMode }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    placeholder = { Text(if (isPhoneMode) "+254 700 000000" else "alex@company.com", color = TextGray) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isPhoneMode) Icons.Default.Phone else Icons.Default.Email,
                            contentDescription = null,
                            tint = TextGray
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = BrandPurple
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isPhoneMode) KeyboardType.Phone else KeyboardType.Email,
                        imeAction = ImeAction.Next
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 4. Password Input
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Password", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                    Text("Forgot password?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BrandPurple)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("At least 8 characters", color = TextGray) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextGray) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Password Visibility",
                                tint = TextGray
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = BrandPurple
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 5. Options Row (Custom Checkbox & End-to-End)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keepSignedIn = !keepSignedIn }) {
                        // Custom Checkbox implementation
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (keepSignedIn) BrandPurple else Color.Transparent)
                                .border(
                                    width = if (keepSignedIn) 0.dp else 1.5.dp,
                                    color = if (keepSignedIn) Color.Transparent else Color.LightGray,
                                    shape = RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (keepSignedIn) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Keep me signed in", fontSize = 13.sp, color = TextGray)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = TextGray, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("End-to-end", fontSize = 12.sp, color = TextGray)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 6. Submit Button
                Button(
                    onClick = {
                        if (identifier.isNotBlank() && password.isNotBlank()) {
                            isLoading = true
                            coroutineScope.launch {
                                try {
                                    val request = LoginRequest(identifier, password, keepSignedIn)
                                    val response = RetrofitClient.instance.login(request)

                                    if (response.isSuccessful) {
                                        val role = response.body()?.role
                                        val token = response.body()?.token

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
                        } else {
                            Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isLoading) "Signing in..." else "Sign In", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (!isLoading) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 7. Footer Texts
        Text(
            text = "By signing in, you agree to our Terms of Service & Privacy Policy.",
            fontSize = 12.sp,
            color = TextGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Secured with 256-bit encryption. Privacy Policy · Terms",
            fontSize = 12.sp,
            color = TextGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}