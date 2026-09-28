@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dropspike.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dropspike.twitch.WatchedGame
import java.text.DateFormat
import java.util.Date

private val WAKE_CHOICES = listOf(0 to "Off", 30 to "30 min", 60 to "1 h", 120 to "2 h")

/** Watched games (mined first, in this order) and the background wake-up check. */
@Composable
fun GamesCard(vm: MainViewModel) {
    val state by vm.games.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reloadGames() }

    Section(4, "Games & schedule") {
        Hint("Watched games are mined first, top to bottom, even if you haven't started their campaign yet. Other in-progress drops are mined after them unless you turn that off.")
        if (state.watched.isEmpty()) {
            StatusLine(null, "No watched games: mining anything already in progress")
        } else {
            state.watched.forEachIndexed { i, g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${g.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (i > 0) IconButton(onClick = { vm.moveWatchedUp(g) }) { Icon(Icons.Default.KeyboardArrowUp, "Move ${g.name} up") }
                    IconButton(onClick = { vm.toggleWatched(g) }) { Icon(Icons.Default.Close, "Stop watching ${g.name}") }
                }
            }
        }
        Buttons {
            FilledTonalButton(onClick = { picking = true }) { Text("Choose games") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only mine watched games", style = MaterialTheme.typography.bodyMedium)
                Hint("Skip other drops you have in progress")
            }
            Switch(checked = state.onlyWatched, onCheckedChange = vm::setOnlyWatched, enabled = state.watched.isNotEmpty())
        }

        Text("Check for live drops in the background", style = MaterialTheme.typography.labelLarge)
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
            Hint("Wakes about every ${WAKE_CHOICES.first { it.first == state.wakeIntervalMin }.second} (Android may delay it while the phone sleeps), starts mining if a game is live, and stops mining after 10 idle minutes. Needs Battery → Unrestricted to start on its own; otherwise you get a tap-to-start notification.")
            StatusLine(null, "Last check: ${state.lastWakeCheck.ifEmpty { "not yet" }}")
        }
    }

    if (picking) GamePicker(state, vm, onDismiss = { picking = false })
}

@Composable
private fun GamePicker(state: GamesState, vm: MainViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val watchedSlugs = state.watched.map { it.slug }.toSet()
    val shown = state.catalog.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose games") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.busy -> Hint("Fetching active campaigns…")
                    state.error != null -> StatusLine(false, state.error)
                    state.catalogAt > 0 -> Hint("${state.catalog.size} games with active campaigns · updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.catalogAt))}")
                    else -> Hint("Tap Refresh to load games with active drops campaigns (uses the integrity token).")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.slug }) { g ->
                        GameRow(g, checked = g.slug in watchedSlugs, onToggle = { vm.toggleWatched(g) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            OutlinedButton(onClick = { vm.refreshGameCatalog() }, enabled = !state.busy) { Text(if (state.busy) "Refreshing…" else "Refresh") }
        },
    )
}

@Composable
private fun GameRow(game: WatchedGame, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(game.name, style = MaterialTheme.typography.bodyMedium)
            if (game.campaigns > 0) Hint("${game.campaigns} active campaign${if (game.campaigns == 1) "" else "s"}")
        }
    }
}
