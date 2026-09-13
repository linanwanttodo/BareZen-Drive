package com.linan.barezen_drive.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.update.UpdateBadge
import com.linan.barezen_drive.ui.theme.avatarColor
import com.linan.barezen_drive.ui.theme.avatarLetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Session-wide avatar bitmap, fetched once per signed-in user and refreshed
 * after an upload. Screens render it through [AvatarButton] in their top bars.
 */
object AvatarStore {
    private val _bitmap = MutableStateFlow<ImageBitmap?>(null)
    val bitmap = _bitmap.asStateFlow()

    private var loadedFor: String? = null

    suspend fun ensure(repo: FilesRepository, userId: String?) {
        if (userId == null) return
        if (loadedFor == userId && _bitmap.value != null) return
        loadedFor = userId
        _bitmap.value = withContext(Dispatchers.Default) {
            runCatching { repo.avatarBytes(userId).getOrThrow().decodeToImageBitmap() }.getOrNull()
        }
    }

    fun invalidate() {
        loadedFor = null
        _bitmap.value = null
    }
}

/**
 * Top-bar avatar: opens Settings, shows the update-available dot (settings is
 * where the update check lives, Google-Photos style). Without a custom photo
 * it renders the account letter on a stable per-username color - the same
 * Google-style circle the settings account card uses, so the top bar never
 * falls back to a gray generic person icon.
 */
@Composable
fun AvatarButton(
    repo: FilesRepository,
    userId: String?,
    username: String? = null,
    size: Dp = 34.dp,
    onOpenSettings: () -> Unit,
) {
    val bmp by AvatarStore.bitmap.collectAsState()
    val badge by UpdateBadge.available.collectAsState()
    LaunchedEffect(userId) { AvatarStore.ensure(repo, userId) }
    Box(
        Modifier
            .size(size + 8.dp)
            .clip(CircleShape)
            .clickable(onClick = onOpenSettings),
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Box(
                Modifier
                    .size(size)
                    .background(avatarColor(username.orEmpty()), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    avatarLetter(username.orEmpty()),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
    }
}
