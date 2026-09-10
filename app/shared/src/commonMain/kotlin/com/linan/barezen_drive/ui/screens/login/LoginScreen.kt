package com.linan.barezen_drive.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.repo.AuthRepository
import com.linan.barezen_drive.platform.isWebPlatform
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.ui.theme.filledButtonColors
import kotlinx.coroutines.launch
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings

@Composable
fun LoginScreen(
    auth: AuthRepository,
    onLoggedIn: () -> Unit,
    themeToggle: (@Composable () -> Unit)? = null,
) {
    var mode by remember { mutableIntStateOf(0) } // 0 login, 1 register
    var host by remember { mutableStateOf(auth.defaultHost()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Surface (not a plain .background modifier): it seeds LocalContentColor
    // with onBackground. Uncolored Text (the title, tabs) would otherwise
    // inherit Compose's default black content color and vanish in dark mode.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Box(Modifier.fillMaxSize()) {
        Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            // widthIn must come BEFORE fillMaxWidth: the reverse order lets
            // fillMaxWidth force the incoming min width (full window) past the
            // widthIn cap, so the form stretches edge-to-edge on wide screens.
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Text("BareZen Drive", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        TabRow(selectedTabIndex = mode) {
            Tab(mode == 0, { mode = 0 }) { Text(LocalStrings.current.actionSignIn, Modifier.padding(12.dp)) }
            Tab(mode == 1, { mode = 1 }) { Text(LocalStrings.current.actionRegister, Modifier.padding(12.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(LocalStrings.current.fieldServerUrlHint) },
            supportingText = {
                Text(
                    if (isWebPlatform()) LocalStrings.current.serverUrlWebHint
                    else LocalStrings.current.serverUrlDeviceHint,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(LocalStrings.current.fieldUsername) },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(LocalStrings.current.fieldPassword) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        // Always rendered in full accent color (the WeChat/GitHub/Google
        // pattern): a permanently gray disabled button is nearly invisible in
        // dark mode. Validation happens on tap instead - empty fields surface
        // the standard error line; the button only disables while submitting.
        Button(
            enabled = !busy,
            colors = filledButtonColors(),
            onClick = {
                if (username.isBlank() || password.isBlank()) {
                    error = when {
                        username.isBlank() && password.isBlank() -> I18n.strings.errorEnterCredentials
                        username.isBlank() -> I18n.strings.errorEnterUsername
                        else -> I18n.strings.errorEnterPassword
                    }
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    val r = if (mode == 0) {
                        auth.login(host, username, password).map { }
                    } else {
                        // The register endpoint creates the account but returns no
                        // tokens; log in right away so the session is authenticated.
                        auth.register(host, username, password)
                            .mapCatching { auth.login(host, username, password).getOrThrow() }
                            .map { }
                    }
                    busy = false
                    r.fold(
                        onSuccess = {
                            // Remember the name for the settings avatar; the
                            // tokens themselves stay in TokenStorage.
                            AppPreferences.get().username = username
                            onLoggedIn()
                        },
                        onFailure = { e -> error = e.message ?: I18n.strings.operationFailedRetry },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    busy -> LocalStrings.current.pleaseWait
                    mode == 0 -> LocalStrings.current.actionSignIn
                    else -> LocalStrings.current.registerAndSignIn
                },
            )
        }
        }
    }
        // Web-only quick theme toggle floats over the top-right corner so the
        // scheme can be flipped before signing in, too. Declared after the
        // form so it draws on top of the opaque form background.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 4.dp),
        ) {
            themeToggle?.invoke()
        }
    }
    }
}