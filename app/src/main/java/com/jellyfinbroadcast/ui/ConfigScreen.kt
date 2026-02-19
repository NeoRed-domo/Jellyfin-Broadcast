package com.jellyfinbroadcast.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val JellyfinBlue = Color(0xFF00A4DC)
private val JellyfinPurple = Color(0xFFAA5CC3)
private val JellyfinGlow = Color(0xFF5EB0E5)
private val DarkBg = Color(0xFF101828)

@Composable
fun ConfigScreen(
    onConnect: (host: String, port: String, username: String, password: String) -> Unit,
    isConnecting: Boolean,
    errorMessage: String?,
    successMessage: String? = null,
    title: String = "Jellyfin Broadcast",
    buttonText: String = "Connecter"
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8096") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Show success overlay
        if (successMessage != null) {
            Card(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkBg)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "✅",
                        fontSize = 48.sp
                    )
                    Text(
                        text = successMessage,
                        color = Color(0xFF4CAF50),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .widthIn(max = 450.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkBg)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        color = JellyfinBlue,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Server Host + Port on the same row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlowTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = "Adresse du serveur",
                            placeholder = "192.168.1.100",
                            modifier = Modifier.weight(3f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Right) }
                            )
                        )

                        GlowTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = "Port",
                            placeholder = "8096",
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                        )
                    }

                    // Username
                    GlowTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Utilisateur",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )

                    // Password
                    GlowTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Mot de passe",
                        modifier = Modifier.fillMaxWidth(),
                        isPassword = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (host.isNotBlank() && username.isNotBlank()) {
                                    onConnect(host, port, username, password)
                                }
                            }
                        )
                    )

                    // Error message
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF6B6B),
                            fontSize = 14.sp
                        )
                    }

                    // Connect button
                    Button(
                        onClick = { onConnect(host, port, username, password) },
                        enabled = host.isNotBlank() && username.isNotBlank() && !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .focusable(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JellyfinBlue,
                            disabledContainerColor = Color(0xFF2A3A4C)
                        )
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = buttonText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) JellyfinGlow else Color.Transparent,
        label = "borderGlow"
    )
    val glowAlpha = if (isFocused) 0.4f else 0f

    Box(
        modifier = modifier
            .then(
                if (isFocused) Modifier.shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = JellyfinBlue.copy(alpha = glowAlpha),
                    spotColor = JellyfinBlue.copy(alpha = glowAlpha)
                ) else Modifier
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color(0xFFBBBBBB),
                cursorColor = JellyfinBlue,
                focusedBorderColor = JellyfinBlue,
                unfocusedBorderColor = Color(0xFF334455),
                focusedLabelColor = JellyfinGlow,
                unfocusedLabelColor = Color(0xFF778899),
                focusedPlaceholderColor = Color(0xFF556677),
                unfocusedPlaceholderColor = Color(0xFF556677)
            ),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
    }
}
