@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.dropspike.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.dropspike.twitch.InvCampaign
import dev.dropspike.twitch.InvDrop
import dev.dropspike.twitch.WatchedGame
import dev.dropspike.twitch.slugOf

/**
 * Everything about one campaign: each drop with its reward, time left, which channels count,
 * and what to do next (link the account, mine it first, open it on Twitch).
 */
@Composable
internal fun CampaignSheet(
    c: InvCampaign,
    miningNow: Boolean,
    firstInWatchList: Boolean,
    onMineFirst: (WatchedGame) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val game = remember(c.gameId) { WatchedGame(c.gameId, c.gameName, c.gameSlug ?: slugOf(c.gameName)) }
    val now = System.currentTimeMillis()
    val earned = c.drops.count { it.claimed || it.done }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameArt(game, 72.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.gameName, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (miningNow) Pill("Mining now", scheme.tertiaryContainer, scheme.onTertiaryContainer, dot = StatusColors.good, live = true)
                        Pill(endsIn(c.endsAtMs, now), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
                        if (!c.accountConnected) Pill("Not linked", scheme.errorContainer, scheme.onErrorContainer)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedCount(earned, MaterialTheme.typography.headlineMedium, scheme.primary)
                    Text(" of ${c.drops.size} drops earned", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                }
                Progress(if (c.drops.isEmpty()) 0f else earned.toFloat() / c.drops.size)
            }

            if (!c.accountConnected) {
                Surface(color = scheme.errorContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Link your ${c.gameName} account", style = MaterialTheme.typography.titleSmall, color = scheme.onErrorContainer)
                        Text(
                            "Twitch only credits these drops once your game account is connected. PocketDrop skips the campaign until then.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onErrorContainer,
                        )
                        c.linkUrl?.let { url -> Button(onClick = { open(url) }) { Text("Link account") } }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Drops", style = MaterialTheme.typography.titleMedium)
                c.drops.sortedBy { it.required }.forEach { DropDetail(it, now) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Channels that count", style = MaterialTheme.typography.titleMedium)
                if (c.allowedLogins.isEmpty()) {
                    Hint("Any live, drops-enabled channel playing ${c.gameName}. PocketDrop picks the most-watched one.")
                } else {
                    Hint("Only these ${c.allowedLogins.size} channels count. Tap one to open it on Twitch.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        c.allowedLogins.forEach { login ->
                            AssistChip(onClick = { open("https://www.twitch.tv/$login") }, label = { Text(login) })
                        }
                    }
                }
            }

            Hint(
                (if (c.startsAtMs > 0) "Started ${formatDay(c.startsAtMs)} · " else "") + "Ends ${formatDay(c.endsAtMs)}, ${formatTime(c.endsAtMs)}",
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptics.confirm()
                        onMineFirst(game)
                    },
                    enabled = !firstInWatchList,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(if (firstInWatchList) Icons.Default.CheckCircle else AppIcons.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (firstInWatchList) "First in your watch list" else "Mine this first")
                }
                OutlinedButton(
                    onClick = { open("https://www.twitch.tv/drops/inventory") },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Open in Twitch inventory") }
            }
        }
    }
}

@Composable
private fun DropDetail(d: InvDrop, now: Long) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        DropImage(d.imageUrl, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                when {
                    d.claimed -> Icon(Icons.Default.CheckCircle, "Claimed", tint = StatusColors.good, modifier = Modifier.size(20.dp))
                    d.done -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    !d.preconditionsMet -> Icon(Icons.Default.Lock, "Locked", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            if (!d.claimed) {
                Progress(
                    if (d.required > 0) d.minutes.toFloat() / d.required else 0f,
                    color = if (d.done) StatusColors.good else scheme.primary,
                )
            }
            Text(
                when {
                    d.claimed -> "Claimed"
                    d.done -> "Earned · claiming"
                    !d.preconditionsMet -> "Unlocks after the previous drop · ${remaining(d.required)}"
                    d.endsAtMs <= now -> "Expired"
                    else -> "${d.minutes} of ${d.required} min · ${remaining(d.required - d.minutes)} to go"
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** A drop's reward picture, with the trophy as placeholder. */
@Composable
internal fun DropImage(url: String?, size: androidx.compose.ui.unit.Dp) {
    val shape = MaterialTheme.shapes.medium
    val placeholder = @Composable {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Trophy, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size / 2))
        }
    }
    if (url == null) {
        placeholder()
        return
    }
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(MotionTokens.MEDIUM).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        loading = { placeholder() },
        error = { placeholder() },
    )
}

/** "Ends in 3 days", "Ends in 5 h", "Ends in 40 min". */
internal fun endsIn(endsAtMs: Long, now: Long = System.currentTimeMillis()): String {
    if (endsAtMs == Long.MAX_VALUE) return "No end date"
    val minutes = (endsAtMs - now) / 60_000
    return when {
        minutes <= 0 -> "Ended"
        minutes < 60 -> "Ends in $minutes min"
        minutes < 48 * 60 -> "Ends in ${minutes / 60} h"
        else -> "Ends in ${minutes / (24 * 60)} days"
    }
}
