package dev.dropspike.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
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
    var openCampaign by rememberSaveable { mutableStateOf<String?>(null) }
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
    fun isMining(c: InvCampaign) = listOfNotNull(status.now, status.second).any { it.game.matches(c.gameId, c.gameName) }
    fun openFor(m: MiningNow) {
        openCampaign = active.firstOrNull { m.game.matches(it.gameId, it.gameName) }?.id
    }

    ScreenList(refreshing = inventory.loading, onRefresh = vm::refreshInventory) {
        item(key = "header") {
            ScreenHeader(
                "PocketDrop",
                account.login?.let { "Signed in as $it" },
                leading = { AppLogo(48.dp) },
            )
        }
        item(key = "hero") { HeroCard(status, games, perms, onOpenGames, onOpenMining = ::openFor) }
        item(key = "setup") { SetupChecklist(games, perms, onOpenGames, vm) }
        item(key = "uptime") {
            Section("Uptime", "What each mining minute achieved", icon = AppIcons.Schedule) {
                UptimeChart(uptime, status.running)
            }
        }
        item(key = "progress-header") {
            Column(Modifier.animateItem().padding(top = 4.dp)) {
                Text("In progress", style = MaterialTheme.typography.titleLarge)
                Hint(
                    when {
                        inventory.loading && active.isEmpty() -> "Updating…"
                        inventory.error != null && !status.running -> "Couldn't update: ${inventory.error}. Pull down to retry."
                        active.isEmpty() -> "Pull down to refresh"
                        else -> "${active.size} campaign${if (active.size == 1) "" else "s"} with drops left · tap one for details"
                    },
                )
            }
        }
        if (active.isEmpty() && !inventory.loading) {
            item(key = "empty") {
                EmptyState(
                    AppIcons.Drop,
                    "No drops in progress",
                    "Watched games start earning as soon as a drops-enabled stream is live.",
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(active, key = { it.id }) { c ->
            CampaignCard(c, mining = isMining(c), onClick = { openCampaign = c.id }, modifier = Modifier.animateItem())
        }
    }

    // Looked up on every recomposition so the sheet's progress stays live while it's open.
    active.firstOrNull { it.id == openCampaign }?.let { c ->
        CampaignSheet(
            c = c,
            miningNow = isMining(c),
            firstInWatchList = games.watched.firstOrNull()?.matches(c.gameId, c.gameName) == true,
            onMineFirst = vm::mineFirst,
            onDismiss = { openCampaign = null },
        )
    }
}

private fun InvDrop.isOpen(now: Long) = !claimed && !done && required > 0 && endsAtMs > now

private enum class HeroMode { Paused, Mining, Searching, Stopped }

private fun MinerStatus.mode() = when {
    running && waiting != null -> HeroMode.Paused
    running && now != null -> HeroMode.Mining
    running -> HeroMode.Searching
    else -> HeroMode.Stopped
}

/** The big status card at the top of Home. Each state has its own tint and content, cross-faded. */
@Composable
private fun HeroCard(
    status: MinerStatus,
    games: GamesState,
    perms: PermissionState,
    onOpenGames: () -> Unit,
    onOpenMining: (MiningNow) -> Unit,
) {
    val context = LocalContext.current
    val prefs = DropSpikeApp.instance.prefs
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val mode = status.mode()
    val tint by animateColorAsState(
        when (mode) {
            HeroMode.Mining -> scheme.tertiaryContainer
            HeroMode.Searching, HeroMode.Paused -> scheme.secondaryContainer
            HeroMode.Stopped -> scheme.primaryContainer
        },
        tween(MotionTokens.LONG),
        label = "heroTint",
    )
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.7f), scheme.surfaceContainerLow)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AnimatedContent(
                targetState = status,
                contentKey = { it.mode() },
                transitionSpec = {
                    (fadeIn(tween(MotionTokens.MEDIUM, delayMillis = 90)) + scaleIn(tween(MotionTokens.MEDIUM, delayMillis = 90), initialScale = 0.96f)) togetherWith
                        fadeOut(tween(90)) using SizeTransform(clip = false)
                },
                label = "hero",
            ) { s ->
                when (s.mode()) {
                    HeroMode.Paused -> PausedHero(s.waiting.orEmpty())
                    HeroMode.Mining -> s.now?.let { MiningHero(it, s.second, onOpenMining) }
                    HeroMode.Searching -> SearchingHero(s.summary)
                    HeroMode.Stopped -> StoppedHero(games)
                }
            }

            AnimatedContent(
                targetState = status.running,
                transitionSpec = { fadeIn(tween(MotionTokens.MEDIUM)) togetherWith fadeOut(tween(MotionTokens.SHORT)) },
                label = "heroButton",
            ) { running ->
                val interaction = remember { MutableInteractionSource() }
                if (running) {
                    OutlinedButton(
                        onClick = {
                            haptics.confirm()
                            if (prefs.autoMine) AutoMine.pauseAfterStop()
                            MinerService.stop(context)
                        },
                        interactionSource = interaction,
                        modifier = Modifier.fillMaxWidth().height(52.dp).pressScale(interaction),
                    ) {
                        Icon(AppIcons.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop mining")
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                if (!perms.notificationsOk) perms.requestNotifications()
                                prefs.sessionActive = false
                                prefs.autoMinePausedUntil = 0
                                MinerService.start(context)
                            },
                            interactionSource = interaction,
                            modifier = Modifier.fillMaxWidth().height(56.dp).pressScale(interaction),
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
                                modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenGames).padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiningHero(mining: MiningNow, second: MiningNow?, onOpen: (MiningNow) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Mining", scheme.tertiaryContainer, scheme.onTertiaryContainer, dot = StatusColors.good, live = true)
            Spacer(Modifier.weight(1f))
            Text(
                "for ${remaining(((System.currentTimeMillis() - mining.since) / 60_000).toInt())}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onOpen(mining) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameArt(mining.game, 88.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(mining.game.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (mining.drop != null && mining.required > 0) {
                    Text(mining.drop, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedCount((mining.minutes * 100 / mining.required).coerceIn(0, 100), MaterialTheme.typography.headlineMedium, scheme.primary, suffix = "%")
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${remaining(mining.required - mining.minutes)} to go",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    Progress(mining.minutes.toFloat() / mining.required)
                } else {
                    Text("The campaign starts once Twitch counts the first minutes", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Details", tint = scheme.onSurfaceVariant)
        }
        Text(
            "Watching ${mining.channel} · ${compact(mining.viewers)} viewers",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        second?.let { SecondChannel(it, onOpen) }
    }
}

@Composable
private fun SearchingHero(summary: String) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadarPulse(scheme.primary, 72.dp) { AppLogo(36.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Searching", scheme.secondaryContainer, scheme.onSecondaryContainer, dot = StatusColors.warning, live = true)
            Text("Looking for a live channel", style = MaterialTheme.typography.titleLarge)
            Hint(summary.ifEmpty { "Checks your games every minute." })
        }
    }
}

@Composable
private fun PausedHero(waiting: String) {
    val scheme = MaterialTheme.colorScheme
    val charger = waiting.contains("charger")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(if (charger) AppIcons.Bolt else AppIcons.Wifi, null, tint = scheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Paused", scheme.secondaryContainer, scheme.onSecondaryContainer, dot = StatusColors.warning)
            Text(waiting.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge)
            Hint("You chose to mine only ${if (charger) "while charging" else "on Wi-Fi"}. It carries on by itself.")
        }
    }
}

@Composable
private fun StoppedHero(games: GamesState) {
    val prefs = DropSpikeApp.instance.prefs
    val scheme = MaterialTheme.colorScheme
    val checking by AutoMine.checking.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (checking) RadarPulse(scheme.primary, 22.dp) { Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.primary)) }
                else Icon(AppIcons.Schedule, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        checking -> "Checking for live drops…"
                        AutoMine.paused(prefs) -> "Auto mine paused after Stop until ${formatTime(prefs.autoMinePausedUntil)}"
                        games.lastWakeCheck.isNotEmpty() -> "Auto mine · last check ${games.lastWakeCheck}"
                        else -> "Auto mine starts by itself when a game goes live"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.animateContentSize(),
                )
            }
        }
    }
}

/** The second channel, when "Watch two channels" is on. */
@Composable
private fun SecondChannel(m: MiningNow, onOpen: (MiningNow) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onOpen(m) },
    ) {
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

/** A campaign at a glance: its next drop and time left. Tap for the full details sheet. */
@Composable
private fun CampaignCard(c: InvCampaign, mining: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val now = System.currentTimeMillis()
    val game = remember(c.gameId) { WatchedGame(c.gameId, c.gameName, c.gameSlug ?: slugOf(c.gameName)) }
    val next = c.drops.filter { it.isOpen(now) }.maxByOrNull { it.minutes.toFloat() / it.required }
        ?: c.drops.firstOrNull { it.done && !it.claimed }
    val claimed = c.drops.count { it.claimed }
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(if (mining) scheme.surfaceContainerHigh else scheme.surfaceContainerLow, label = "card")
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = modifier.fillMaxWidth().pressScale(interaction, 0.98f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameArt(game, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.gameName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (mining) {
                            Spacer(Modifier.width(8.dp))
                            Pill("Now", scheme.tertiaryContainer, scheme.onTertiaryContainer, dot = StatusColors.good, live = true)
                        } else if (!c.accountConnected) {
                            Spacer(Modifier.width(8.dp))
                            Pill("Not linked", scheme.errorContainer, scheme.onErrorContainer)
                        }
                    }
                    Text(c.name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$claimed of ${c.drops.size} claimed · ${endsIn(c.endsAtMs, now)}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
            }
            next?.let { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropImage(d.imageUrl, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row {
                            Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (d.done) "Claiming" else "${d.minutes}/${d.required} min",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Progress(
                            if (d.required > 0) d.minutes.toFloat() / d.required else 0f,
                            color = if (d.done) StatusColors.good else scheme.primary,
                        )
                    }
                }
            }
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
