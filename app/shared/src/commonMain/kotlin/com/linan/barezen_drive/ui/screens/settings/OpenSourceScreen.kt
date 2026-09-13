package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.platform.openInBrowser
import com.linan.barezen_drive.i18n.LocalStrings

private const val GITHUB_PROFILE_URL = "https://github.com/linanwanttodo"

private data class OpenSourceEntry(val name: String, val license: String)

// Every open-source component shipped by the app (client and server side),
// one row each, plus the license of the app itself.
private val OPEN_SOURCE_ENTRIES = listOf(
    OpenSourceEntry("Kotlin", "Apache License 2.0"),
    OpenSourceEntry("Compose Multiplatform", "Apache License 2.0"),
    OpenSourceEntry("Compose Material 3", "Apache License 2.0"),
    OpenSourceEntry("material3-adaptive-navigation-suite", "Apache License 2.0"),
    OpenSourceEntry("Backdrop (Kyant0)", "Apache License 2.0"),
    OpenSourceEntry("Ktor Client / Server", "Apache License 2.0"),
    OpenSourceEntry("kotlinx.serialization", "Apache License 2.0"),
    OpenSourceEntry("kotlinx.coroutines", "Apache License 2.0"),
    OpenSourceEntry("kotlinx-datetime / kotlin-wrappers", "Apache License 2.0"),
    OpenSourceEntry("AndroidX Activity / Lifecycle / Security Crypto", "Apache License 2.0"),
    OpenSourceEntry("Exposed ORM", "Apache License 2.0"),
    OpenSourceEntry("HikariCP", "Apache License 2.0"),
    OpenSourceEntry("BCrypt (at.favre)", "Apache License 2.0"),
    OpenSourceEntry("Logback", "EPL 1.0 / LGPL 2.1"),
    OpenSourceEntry("H2 Database", "EPL 1.0 / MPL 2.0"),
    OpenSourceEntry("PostgreSQL JDBC Driver", "BSD-2-Clause"),
    OpenSourceEntry("JUnit", "Eclipse Public License 1.0"),
)

/** Full-screen credits page listing the open-source stack and the app license. */
@Composable
fun OpenSourceScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.openSourceNotices) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        LocalStrings.current.openSourceIntro,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        LocalStrings.current.licenseNotice,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(OPEN_SOURCE_ENTRIES) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        entry.license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openInBrowser(GITHUB_PROFILE_URL) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        LocalStrings.current.copyrightHolder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.Code,
                        contentDescription = LocalStrings.current.openGitHubProfile,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
