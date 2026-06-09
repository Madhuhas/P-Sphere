package com.psphere.ui.register

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.psphere.BuildConfig

private val DEVICE_TYPES = listOf("android", "ios", "desktop")
private val DEVICE_TYPE_LABELS = mapOf(
    "android" to "Android phone / tablet",
    "ios"     to "iPhone / iPad",
    "desktop" to "Desktop / laptop",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterDeviceScreen(
    onRegistered: () -> Unit,
    viewModel: RegisterDeviceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Server section
    var serverExpanded by remember { mutableStateOf(false) }
    var serverUrl      by remember { mutableStateOf(BuildConfig.BASE_URL) }

    // Login / Register tab (0 = Login, 1 = Create Account)
    var selectedTab by remember { mutableIntStateOf(0) }

    // Fields
    var email      by remember { mutableStateOf("") }
    var password   by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(viewModel.defaultDeviceName) }
    var deviceType by remember { mutableStateOf(DEVICE_TYPES[0]) }
    var typeExpanded by remember { mutableStateOf(false) }
    var submitted  by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        when (state) {
            is RegisterState.Success -> onRegistered()
            is RegisterState.Error   -> {
                snackbar.showSnackbar((state as RegisterState.Error).message)
                viewModel.resetState()
            }
            else -> Unit
        }
    }

    val isLoading = state is RegisterState.CheckingHealth || state is RegisterState.Registering

    Scaffold(
        topBar       = { TopAppBar(title = { Text("Welcome to PSphere") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    if (state is RegisterState.CheckingHealth) "Reaching server…" else "Registering device…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                // ── P2P mode card ─────────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(2.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                            Column {
                                Text("P2P Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("No account or server needed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Sync photos directly with other PSphere phones on the same Wi-Fi. Works completely offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.skipToP2p(context) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Use P2P Mode — No Server Required")
                        }
                    }
                }

                // ── Core server card ──────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TextButton(
                            onClick  = { serverExpanded = !serverExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Connect to a PSphere Core server",
                                modifier  = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                                style     = MaterialTheme.typography.bodyMedium,
                            )
                            Icon(
                                if (serverExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (serverExpanded) "Collapse" else "Expand",
                            )
                        }

                        AnimatedVisibility(visible = serverExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(
                                modifier = Modifier.padding(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Divider()
                                Spacer(Modifier.height(4.dp))

                                // Server URL
                                OutlinedTextField(
                                    value           = serverUrl,
                                    onValueChange   = { serverUrl = it; submitted = false },
                                    label           = { Text("Server URL") },
                                    placeholder     = { Text("http://192.168.1.10:3000") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    isError         = submitted && serverUrl.isBlank(),
                                    modifier        = Modifier.fillMaxWidth(),
                                    singleLine      = true,
                                )

                                // Login vs Create Account tabs
                                TabRow(selectedTabIndex = selectedTab) {
                                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; submitted = false }, text = { Text("Log In") })
                                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; submitted = false }, text = { Text("Create Account") })
                                }

                                Spacer(Modifier.height(4.dp))

                                OutlinedTextField(
                                    value           = email,
                                    onValueChange   = { email = it; submitted = false },
                                    label           = { Text("Email") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    isError         = submitted && email.isBlank(),
                                    supportingText  = if (submitted && email.isBlank()) { { Text("Email is required") } } else null,
                                    modifier        = Modifier.fillMaxWidth(),
                                    singleLine      = true,
                                )
                                OutlinedTextField(
                                    value                = password,
                                    onValueChange        = { password = it; submitted = false },
                                    label                = { Text("Password") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    isError              = submitted && password.isBlank(),
                                    supportingText       = if (submitted && password.isBlank()) { { Text("Password is required") } } else null,
                                    modifier             = Modifier.fillMaxWidth(),
                                    singleLine           = true,
                                )
                                OutlinedTextField(
                                    value          = deviceName,
                                    onValueChange  = { deviceName = it; submitted = false },
                                    label          = { Text("Device name") },
                                    isError        = submitted && deviceName.isBlank(),
                                    supportingText = if (submitted && deviceName.isBlank()) { { Text("Device name is required") } } else null,
                                    modifier       = Modifier.fillMaxWidth(),
                                    singleLine     = true,
                                )

                                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                                    OutlinedTextField(
                                        value         = DEVICE_TYPE_LABELS[deviceType] ?: deviceType,
                                        onValueChange = {},
                                        readOnly      = true,
                                        label         = { Text("Device type") },
                                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                                        modifier      = Modifier.menuAnchor().fillMaxWidth(),
                                    )
                                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                        DEVICE_TYPES.forEach { type ->
                                            DropdownMenuItem(
                                                text    = { Text(DEVICE_TYPE_LABELS[type] ?: type) },
                                                onClick = { deviceType = type; typeExpanded = false },
                                            )
                                        }
                                    }
                                }

                                FilledTonalButton(
                                    onClick  = {
                                        submitted = true
                                        if (selectedTab == 0) {
                                            viewModel.login(context, serverUrl, email, password, deviceName, deviceType)
                                        } else {
                                            viewModel.createAccount(context, serverUrl, email, password, deviceName, deviceType)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (selectedTab == 0) "Log In & Connect" else "Create Account & Connect")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
