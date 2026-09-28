@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dropspike.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dropspike.twitch.WatchedGame
import java.text.DateFormat
import java.util.Date

private val WAKE_CHOICES = listOf(0 to "Off", 30 to "30 min", 60 to "1 h", 120 to "2 h")

@Composable
fun GamesScreen(vm: MainViewModel) {
    val state by vm.games.collectAsStateWithLifecycle()
    val perms = rememberPermissionState()
    var picking by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reloadGames() }

    ScreenList {
        item {
            ScreenHeader("Games", "Mined first, top to bottom") {
                FilledTonalButton(onClick = { picking = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
        }
        item {
            Section("Watch list", "${state.watched.size} game${if (state.watched.size == 1) "" else "s"}", icon = AppIcons.Gamepad) {
                if (state.watched.isEmpty()) {
                    EmptyState(AppIcons.Gamepad, "No games yet", "Add the games whose drops you want. They're mined even before you've started their campaign.") {
                        FilledTonalButton(onClick = { picking = true }) { Text("Choose games") }
                    }
                } else {
                    state.watched.forEachIndexed { i, g ->
                        WatchedRow(
                            game = g,
                            first = i == 0,
                            last = i == state.watched.lastIndex,
                            onUp = { vm.moveWatchedUp(g) },
                            onDown = { vm.moveWatchedDown(g) },
                            onRemove = { vm.toggleWatched(g) },
                        )
                    }
                }
                SettingRow(
                    "Only mine these games",
                    "Otherwise drops you already have in progress are mined after them",
                ) {
                    Switch(checked = state.onlyWatched, onCheckedChange = vm::setOnlyWatched, enabled = state.watched.isNotEmpty())
                }
            }
        }
        item {
            Section("Background checks", "Start mining on its own when a game goes live", icon = AppIcons.Schedule) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    WAKE_CHOICES.forEachIndexed { i, (minutes, label) ->
                        SegmentedButton(
                            selected = state.wakeIntervalMin == minutes,
                            onClick = { vm.setWakeInterval(minutes) },
                            shape = SegmentedButtonDefaults.itemShape(i, WAKE_CHOICES.size),
                        ) { Text(label) }
                    }
                }
                if (state.wakeIntervalMin > 0) {
                    Hint("Mining stops by itself after 10 idle minutes and the next check starts it again. Android may run checks late while the phone sleeps.")
                    SettingRow("Last check", state.lastWakeCheck.ifEmpty { "Not yet" }, icon = AppIcons.Schedule)
                    if (!perms.batteryOk) {
                        SettingRow("Allow background use", "Without it Android only lets checks send a tap-to-start notification") {
                            FilledTonalButton(onClick = perms.requestBattery) { Text("Allow") }
                        }
                    }
                }
            }
        }
    }

    if (picking) GamePicker(state, vm, onDismiss = { picking = false })
}

@Composable
private fun WatchedRow(game: WatchedGame, first: Boolean, last: Boolean, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        GameArt(game, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(game.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (game.needsLink) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(CircleShape).clickable(enabled = game.linkUrl != null) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(game.linkUrl)))
                    },
                ) {
                    Pill(if (game.linkUrl != null) "Link account to earn" else "Needs account link", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
            } else if (game.campaigns > 0) {
                Hint("${game.campaigns} active campaign${if (game.campaigns == 1) "" else "s"}")
            }
        }
        IconButton(onClick = onUp, enabled = !first) { Icon(Icons.Default.KeyboardArrowUp, "Move ${game.name} up") }
        IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Default.KeyboardArrowDown, "Move ${game.name} down") }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove ${game.name}") }
    }
}

/** Full-screen, searchable grid of every game with an active campaign. */
@Composable
private fun GamePicker(state: GamesState, vm: MainViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val watched = state.watched.map { it.slug }.toSet()
    val shown = state.catalog.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Choose games") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                    actions = {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        } else {
                            IconButton(onClick = { vm.refreshGameCatalog() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        }
                        TextButton(onClick = onDismiss) { Text("Done") }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search ${state.catalog.size} games") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    when {
                        state.error != null -> StatusLine(false, state.error)
                        state.busy && state.catalog.isEmpty() -> Hint("Loading games with active campaigns…")
                        state.catalogAt > 0 -> Hint("${watched.size} selected · list updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.catalogAt))}")
                    }
                    if (state.catalog.isEmpty() && !state.busy) {
                        EmptyState(Icons.Default.Search, "No games loaded", "Refresh to load every game with an active drops campaign.") {
                            FilledTonalButton(onClick = { vm.refreshGameCatalog() }) { Text("Refresh") }
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(shown, key = { it.slug }) { g ->
                            GameTile(g, selected = g.slug in watched, onToggle = { vm.toggleWatched(g) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameTile(game: WatchedGame, selected: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onToggle).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            GameArt(
                game,
                96.dp,
                modifier = if (selected) Modifier.border(BorderStroke(3.dp, scheme.primary), RoundedCornerShape(16.dp)) else Modifier,
            )
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, "Selected", tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            game.name,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            game.needsLink -> Text("Needs account link", style = MaterialTheme.typography.labelSmall, color = scheme.error, textAlign = TextAlign.Center)
            game.campaigns > 0 -> Text("${game.campaigns} campaign${if (game.campaigns == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}
