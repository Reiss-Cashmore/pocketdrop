package dev.dropspike.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.UptimeLog
import dev.dropspike.service.AutoMine
import dev.dropspike.service.MinerService
import dev.dropspike.service.MinerState
import dev.dropspike.service.MinerStatus
import dev.dropspike.service.MiningNow
import dev.dropspike.twitch.InvCampaign
import dev.dropspike.twitch.InvDrop
import dev.dropspike.twitch.WatchedGame
import dev.dropspike.twitch.slugOf

@Composable
fun HomeScreen(vm: MainViewModel, onOpenGames: () -> Unit) {
    val status by MinerState.status.collectAsStateWithLifecycle()
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val uptime by UptimeLog.entries.collectAsStateWithLifecycle()
    val perms = rememberPermissionState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!MinerState.status.value.running) vm.refreshInventory() }

    // While mining, the service already reads the inventory every minute.
    val campaigns = if (status.running && status.campaigns.isNotEmpty()) status.campaigns else inventory.campaigns
    val now = System.currentTimeMillis()
    val active = campaigns
        .filter { c -> c.endsAtMs > now && c.drops.any { it.isOpen(now) || (it.done && !it.claimed) } }
        .sortedWith(
            compareByDescending<InvCampaign> { status.now?.game?.matches(it.gameId, it.gameName) == true }
                .thenByDescending { c -> c.drops.filter { it.isOpen(now) }.maxOfOrNull { it.minutes.toFloat() / it.required } ?: 0f },
        )

    ScreenList {
        item { ScreenHeader("PocketDrop", account.login?.let { "Signed in as $it" }, leading = { AppLogo(48.dp) }) }
        item { HeroCard(status, games, perms, onOpenGames) }
        item { SetupChecklist(games, perms, onOpenGames, vm) }
        item {
            Section("Uptime", "What each mining minute achieved", icon = AppIcons.Schedule) {
                UptimeChart(uptime, status.running)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("In progress", style = MaterialTheme.typography.titleLarge)
                    Hint(
                        when {
                            inventory.loading -> "Updating…"
                            inventory.error != null && !status.running -> "Couldn't update: ${inventory.error}"
                            else -> "${active.size} campaign${if (active.size == 1) "" else "s"} with drops left"
                        },
                    )
                }
                if (!status.running) {
                    IconButton(onClick = vm::refreshInventory, enabled = !inventory.loading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            }
        }
        if (active.isEmpty() && !inventory.loading) {
            item {
                EmptyState(
                    AppIcons.Drop,
                    "No drops in progress",
                    "Watched games start earning as soon as a drops-enabled stream is live.",
                )
            }
        }
        items(active, key = { it.id }) { c -> CampaignCard(c, mining = status.now?.game?.matches(c.gameId, c.gameName) == true) }
    }
}

private fun InvDrop.isOpen(now: Long) = !claimed && !done && required > 0 && endsAtMs > now

/** The big status card at the top of Home. */
@Composable
private fun HeroCard(status: MinerStatus, games: GamesState, perms: PermissionState, onOpenGames: () -> Unit) {
    val context = LocalContext.current
    val prefs = DropSpikeApp.instance.prefs
    val scheme = MaterialTheme.colorScheme
    val mining = status.now
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surfaceContainerLow)))
                .padding(20.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                status.running && status.waiting != null -> {
                    Pill("Paused", scheme.secondaryContainer, scheme.onSecondaryContainer, dot = StatusColors.warning)
                    Text(status.waiting.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineSmall)
                    Hint("Your mining settings only allow mining ${if (status.waiting.contains("charger")) "while charging" else "on Wi-Fi"}. It resumes by itself.")
                }
                status.running && mining != null -> {
                    Pill("Mining", scheme.tertiaryContainer, scheme.onTertiaryContainer, dot = StatusColors.good)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GameArt(mining.game, 84.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(mining.game.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (mining.drop != null && mining.required > 0) {
                                Text(mining.drop, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Progress(mining.minutes.toFloat() / mining.required)
                                Text(
                                    "${mining.minutes} of ${mining.required} min · ${remaining(mining.required - mining.minutes)} to go",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant,
                                )
                            } else {
                                Text("Campaign starts once Twitch counts the first minutes", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        "Watching ${mining.channel} · ${compact(mining.viewers)} viewers · for ${remaining(((System.currentTimeMillis() - mining.since) / 60_000).toInt())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    status.second?.let { second -> SecondChannel(second) }
                }
                status.running -> {
                    Pill("Searching", scheme.secondaryContainer, scheme.onSecondaryContainer, dot = StatusColors.warning)
                    Text("Looking for a live channel", style = MaterialTheme.typography.headlineSmall)
                    Hint(status.summary.ifEmpty { "Checks your games every minute." })
                }
                else -> {
                    Pill("Stopped", scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
                    Text("Not mining", style = MaterialTheme.typography.headlineSmall)
                    Hint(
                        if (games.watched.isEmpty()) "Pick the games you want, or just start: drops you already have in progress are mined too."
                        else "Up first: ${games.watched.take(3).joinToString { it.name }}${if (games.watched.size > 3) " and ${games.watched.size - 3} more" else ""}.",
                    )
                    if (prefs.sessionActive) {
                        StatusLine(false, "The last session ended unexpectedly at ${formatTime(prefs.lastTickAt)}")
                    }
                    if (games.autoMine) {
                        val checking by AutoMine.checking.collectAsStateWithLifecycle()
                        Text(
                            when {
                                checking -> "Auto mine: checking for live drops…"
                                AutoMine.paused(prefs) -> "Auto mine paused after Stop until ${formatTime(prefs.autoMinePausedUntil)}"
                                games.lastWakeCheck.isNotEmpty() -> "Auto mine is on · last check ${games.lastWakeCheck}"
                                else -> "Auto mine is on: mining starts by itself when a game goes live"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (status.running) {
                OutlinedButton(
                    onClick = {
                        if (prefs.autoMine) AutoMine.pauseAfterStop()
                        MinerService.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(AppIcons.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop mining")
                }
            } else {
                Button(
                    onClick = {
                        if (!perms.notificationsOk) perms.requestNotifications()
                        prefs.sessionActive = false
                        prefs.autoMinePausedUntil = 0
                        MinerService.start(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start mining", style = MaterialTheme.typography.titleMedium)
                }
                if (games.watched.isEmpty()) {
                    Text(
                        "Choose games",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        modifier = Modifier.clickable(onClick = onOpenGames).padding(4.dp),
                    )
                }
            }
        }
    }
}

/** The second channel, when "Watch two channels" is on. */
@Composable
private fun SecondChannel(m: MiningNow) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GameArt(m.game, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Also: ${m.game.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.drop != null && m.required > 0) {
                    Progress(m.minutes.toFloat() / m.required)
                    Text("${m.drop} · ${m.minutes}/${m.required} min · ${m.channel}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("Waiting for Twitch to count minutes · ${m.channel}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Only the things that stop background mining from working; hidden when all is well. */
@Composable
private fun SetupChecklist(games: GamesState, perms: PermissionState, onOpenGames: () -> Unit, vm: MainViewModel) {
    val todo = buildList {
        if (!perms.notificationsOk) add(Triple("Allow notifications", "Needed to keep mining in the background", "Allow" to perms.requestNotifications))
        if (!perms.batteryOk) add(Triple("Allow background use", "Otherwise Android pauses mining when the screen is off", "Allow" to perms.requestBattery))
        if (!games.autoMine || games.wakeIntervalMin == 0) add(Triple("Mine automatically", "Start mining on its own when a watched game goes live", "Turn on" to { vm.setAutoMine(true) }))
        if (games.watched.isEmpty()) add(Triple("Choose your games", "Mined first, as soon as they're live", "Choose" to onOpenGames))
    }
    if (todo.isEmpty()) return
    Section("Finish setting up", "${todo.size} thing${if (todo.size == 1) "" else "s"} left", icon = Icons.Default.Notifications) {
        todo.forEach { (title, body, action) ->
            SettingRow(title, body, icon = Icons.Default.Warning) {
                FilledTonalButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}

@Composable
private fun CampaignCard(c: InvCampaign, mining: Boolean) {
    var expanded by rememberSaveable(c.id) { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val game = remember(c.gameId) { WatchedGame(c.gameId, c.gameName, c.gameSlug ?: slugOf(c.gameName)) }
    val next = c.drops.filter { it.isOpen(now) }.maxByOrNull { it.minutes.toFloat() / it.required }
    val claimed = c.drops.count { it.claimed }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (mining) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameArt(game, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.gameName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (mining) {
                            Spacer(Modifier.width(8.dp))
                            Pill("Now", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                    Text(c.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$claimed of ${c.drops.size} drops claimed · ends ${formatDay(c.endsAtMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (next != null && !expanded) DropRow(next)
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    c.drops.sortedBy { it.required }.forEach { DropRow(it) }
                }
            }
        }
    }
}

@Composable
private fun DropRow(d: InvDrop) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                d.claimed -> Icon(Icons.Default.CheckCircle, "Claimed", tint = StatusColors.good, modifier = Modifier.size(18.dp))
                d.done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Claiming", style = MaterialTheme.typography.labelSmall)
                }
                !d.preconditionsMet -> Text("Locked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("${d.minutes}/${d.required} min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!d.claimed) {
            Progress(
                if (d.required > 0) d.minutes.toFloat() / d.required else 0f,
                color = if (d.done) StatusColors.good else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal fun remaining(minutes: Int): String = when {
    minutes <= 0 -> "0 min"
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

internal fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fk".format(n / 1_000f)
    else -> n.toString()
}

internal fun formatDay(ms: Long): String =
    if (ms == Long.MAX_VALUE) "—" else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
