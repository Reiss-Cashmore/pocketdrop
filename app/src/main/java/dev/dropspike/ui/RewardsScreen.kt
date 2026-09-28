package dev.dropspike.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import dev.dropspike.data.ClaimLog

/** Everything earned: PocketDrop's own claims (kept on the phone) and the account's full reward list. */
@Composable
fun RewardsScreen(vm: MainViewModel) {
    val rewards by vm.rewards.collectAsStateWithLifecycle()
    val claims by ClaimLog.entries.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - rewards.updatedAt > 5 * 60_000) vm.refreshRewards()
    }
    val weekAgo = System.currentTimeMillis() - 7 * DateUtils.DAY_IN_MILLIS

    ScreenList {
        item {
            ScreenHeader("Rewards", "Everything you've earned") {
                IconButton(onClick = { vm.refreshRewards() }, enabled = !rewards.loading) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Claimed by PocketDrop", claims.size.toString(), Modifier.weight(1f))
                Stat("This week", claims.count { it.atMs >= weekAgo }.toString(), Modifier.weight(1f))
                Stat("On your account", if (rewards.updatedAt == 0L) "…" else rewards.account.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Section("Claimed by PocketDrop", "Newest first", icon = AppIcons.Drop) {
                if (claims.isEmpty()) {
                    EmptyState(AppIcons.Trophy, "Nothing claimed yet", "Finished drops are claimed automatically and appear here.")
                } else {
                    claims.asReversed().forEach { c ->
                        RewardRow(c.imageUrl, c.drop, "${c.game} · ${c.campaign}", c.atMs)
                    }
                }
            }
        }
        item {
            Section("On your Twitch account", "Every drop reward, including ones claimed elsewhere", icon = AppIcons.Trophy) {
                when {
                    rewards.error != null && rewards.account.isEmpty() -> StatusLine(false, "Couldn't load: ${rewards.error}")
                    rewards.account.isEmpty() && rewards.loading -> Hint("Loading…")
                    rewards.account.isEmpty() -> Hint("No rewards on the account yet.")
                    else -> {
                        val shown = if (showAll) rewards.account else rewards.account.take(PREVIEW)
                        shown.forEach { r ->
                            RewardRow(r.imageUrl, r.name + if (r.count > 1) " ×${r.count}" else "", r.gameName.ifEmpty { "Twitch drop" }, r.lastAwardedAtMs)
                        }
                        if (rewards.account.size > PREVIEW) {
                            TextButton(onClick = { showAll = !showAll }) {
                                Text(if (showAll) "Show fewer" else "Show all ${rewards.account.size}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PREVIEW = 30

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun RewardRow(imageUrl: String?, title: String, subtitle: String, atMs: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RewardImage(imageUrl)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (atMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                DateUtils.getRelativeTimeSpanString(atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RewardImage(url: String?) {
    val shape = MaterialTheme.shapes.medium
    val placeholder = @Composable {
        Box(Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Trophy, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    if (url == null) {
        placeholder()
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        loading = { placeholder() },
        error = { placeholder() },
    )
}
