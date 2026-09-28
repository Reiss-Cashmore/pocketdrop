package dev.dropspike.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dropspike.data.ClaimEntry
import dev.dropspike.data.ClaimLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything earned: PocketDrop's own claims (kept on the phone) and the account's full reward list. */
@Composable
fun RewardsScreen(vm: MainViewModel) {
    val rewards by vm.rewards.collectAsStateWithLifecycle()
    val claims by ClaimLog.entries.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (System.currentTimeMillis() - vm.rewards.value.updatedAt > 5 * 60_000) vm.refreshRewards()
    }
    val weekAgo = System.currentTimeMillis() - 7 * DateUtils.DAY_IN_MILLIS
    val recent = remember(claims) { claims.asReversed() }
    val shown = if (showAll) rewards.account else rewards.account.take(PREVIEW)
    val byMonth = remember(shown) { shown.groupBy { monthOf(it.lastAwardedAtMs) } }

    ScreenList(refreshing = rewards.loading, onRefresh = vm::refreshRewards) {
        item(key = "header") { ScreenHeader("Rewards", "Everything you've earned", leading = { AppLogo(40.dp) }) }
        item(key = "stats") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Claimed by PocketDrop", claims.size, Modifier.weight(1f))
                Stat("This week", claims.count { it.atMs >= weekAgo }, Modifier.weight(1f))
                Stat("On your account", rewards.account.size.takeIf { rewards.updatedAt > 0 }, Modifier.weight(1f))
            }
        }
        item(key = "recent") {
            Section("Claimed by PocketDrop", if (recent.isEmpty()) null else "Newest first", icon = AppIcons.Drop) {
                if (recent.isEmpty()) {
                    EmptyState(AppIcons.Trophy, "Nothing claimed yet", "Finished drops are claimed automatically and show up here.")
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 4.dp),
                    ) {
                        items(recent, key = { "${it.atMs}-${it.drop}" }) { ClaimCard(it, Modifier.animateItem()) }
                    }
                }
            }
        }
        item(key = "account-header") {
            Column(Modifier.animateItem().padding(top = 4.dp)) {
                Text("On your Twitch account", style = MaterialTheme.typography.titleLarge)
                Hint(
                    when {
                        rewards.error != null && rewards.account.isEmpty() -> "Couldn't load: ${rewards.error}. Pull down to retry."
                        rewards.account.isEmpty() && rewards.loading -> "Loading…"
                        rewards.account.isEmpty() -> "No rewards on the account yet."
                        else -> "Every drop reward, including ones claimed elsewhere"
                    },
                )
            }
        }
        byMonth.forEach { (month, list) ->
            item(key = "m-$month") {
                Text(
                    month,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.animateItem().padding(start = 4.dp, top = 4.dp),
                )
            }
            items(list, key = { "r-${it.id}" }) { r ->
                RewardRow(
                    r.imageUrl,
                    r.name,
                    r.gameName.ifEmpty { "Twitch drop" },
                    r.lastAwardedAtMs,
                    count = r.count,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (rewards.account.size > PREVIEW) {
            item(key = "more") {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.animateItem().fillMaxWidth()) {
                    Text(if (showAll) "Show fewer" else "Show all ${rewards.account.size}")
                }
            }
        }
    }
}

private const val PREVIEW = 40
private val MONTH = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

private fun monthOf(ms: Long) = if (ms <= 0) "Earlier" else MONTH.format(Date(ms))

@Composable
private fun Stat(label: String, value: Int?, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (value == null) {
                Text("…", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            } else {
                AnimatedCount(value, MaterialTheme.typography.headlineSmall, MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** A recent claim, as a card in the carousel. */
@Composable
private fun ClaimCard(c: ClaimEntry, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.width(148.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DropImage(c.imageUrl, 124.dp)
            Text(c.drop, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.game, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relative(c.atMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RewardRow(imageUrl: String?, title: String, subtitle: String, atMs: Long, count: Int, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DropImage(imageUrl, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (count > 1) Pill("×$count", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                if (atMs > 0) Text(relative(atMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun relative(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
