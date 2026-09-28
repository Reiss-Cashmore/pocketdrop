@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dropspike.ui

// Advanced tools behind Settings → Advanced: token minting, the gate test and diagnostics.

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import dev.dropspike.data.ReportBuilder
import dev.dropspike.data.UptimeLog
import dev.dropspike.service.MinerService
import dev.dropspike.service.MinerState
import dev.dropspike.twitch.DeviceClient
import dev.dropspike.twitch.MintPage
import dev.dropspike.twitch.MintSurface
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DeviceLoginDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val state by vm.deviceLogin.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    val code = state.code

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in with code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    code != null -> {
                        Text("Enter this code at twitch.tv/activate, in any browser or on another device:")
                        Text(
                            code.userCode,
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Waiting for Twitch… (${code.client.label} client)", modifier = Modifier.padding(start = 8.dp))
                        }
                        Buttons {
                            FilledTonalButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)))
                            }) { Text("Open activate page") }
                            OutlinedButton(onClick = { clipScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Twitch code", code.userCode))) } }) { Text("Copy code") }
                        }
                    }
                    state.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Requesting a code…", modifier = Modifier.padding(start = 8.dp))
                    }
                    else -> {
                        state.error?.let { StatusLine(false, it) }
                        Hint("Smart TV is the known-good option: inventory and progress work, though Twitch hides the campaign list from it. Mobile web tokens were rejected by Twitch's API in testing.")
                        Buttons {
                            DeviceClient.entries.forEach { c ->
                                FilledTonalButton(onClick = { vm.startDeviceLogin(c) }) { Text(c.label) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- 2. Integrity -------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun IntegrityCard(vm: MainViewModel) {
    val state by vm.integrity.collectAsStateWithLifecycle()

    Section("Integrity token", "Unlocks the full campaign list and drop claims") {
        Hint("Loads Twitch's anti-bot script in a WebView and asks it for a Client-Integrity token, the way Streamlink does with desktop Chrome.")
        Text("Page", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MintPage.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = state.page == p,
                    onClick = { vm.setMintPage(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, MintPage.entries.size),
                    enabled = !state.busy,
                ) { Text(p.label) }
            }
        }
        Text("WebView", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MintSurface.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = state.surface == s,
                    onClick = { vm.setMintSurface(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, MintSurface.entries.size),
                    enabled = !state.busy,
                ) { Text(s.label) }
            }
        }

        when {
            state.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Minting…", modifier = Modifier.padding(start = 8.dp))
            }
            state.error != null -> StatusLine(false, state.error!!)
            state.expiresAt > System.currentTimeMillis() -> {
                StatusLine(state.isBadBot == "false", "Token valid until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.expiresAt))}")
                Hint("is_bad_bot = ${state.isBadBot ?: "unknown"}  (Twitch's own verdict inside the token)")
                state.probe?.let { p ->
                    StatusLine(
                        p.campaigns != null,
                        "In-WebView campaign check: " +
                            (p.campaigns?.let { "$it campaigns" } ?: "null") +
                            (if (p.errors.isNotEmpty()) " · ${p.errors.joinToString()}" else ""),
                    )
                }
            }
            state.expiresAt > 0 -> StatusLine(false, "Token expired")
            else -> StatusLine(null, "No token yet")
        }

        Buttons {
            FilledTonalButton(onClick = vm::mint, enabled = !state.busy) { Text("Mint token") }
        }
    }
}

/**
 * On-screen mint WebView, shown in a plain Android dialog window rather than inside Compose:
 * on Android 17 with Vanadium, WebViews hosted in Compose loaded pages but never drew.
 * The view model does the minting; this only supplies the view.
 */
@Composable
internal fun MintOverlay(vm: MainViewModel) {
    val state by vm.integrity.collectAsStateWithLifecycle()
    val pending = state.pendingVisibleMint ?: return
    val context = LocalContext.current
    DisposableEffect(pending) {
        val dp = context.resources.displayMetrics.density
        val webView = WebView(context).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            addView(android.widget.TextView(context).apply { text = "Running Twitch's security check…" })
            addView(webView, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (320 * dp).toInt()))
        }
        val dialog = android.app.Dialog(context).apply {
            setContentView(layout)
            setCancelable(false)
            show()
        }
        vm.attachMintHost(webView)
        onDispose {
            dialog.dismiss()
            webView.destroy()
        }
    }
}

// ---- 3. Gate test -------------------------------------------------------------------------

@Composable
internal fun GateCard(vm: MainViewModel) {
    val state by vm.gate.collectAsStateWithLifecycle()

    Section("Campaign gate test", "Compares Twitch's answers with and without the token") {
        Hint("Checks that the account works at all (inventory), then asks for the campaign list without and with the integrity token, claiming to be www.twitch.tv and m.twitch.tv. Any \"With token\" row listing campaigns means the approach works.")
        if (state.rows.isEmpty()) StatusLine(null, "Not run yet")
        state.rows.forEach { StatusLine(it.ok, "${it.label}: ${it.text}") }
        state.campaigns?.takeIf { it.isNotEmpty() }?.let { campaigns ->
            HorizontalDivider()
            campaigns.sortedBy { it.game }.take(12).forEach {
                Text("${it.game} · ${it.name}", style = MaterialTheme.typography.bodySmall)
            }
            if (campaigns.size > 12) Hint("…and ${campaigns.size - 12} more")
        }
        Buttons {
            FilledTonalButton(onClick = { vm.runGateTest() }, enabled = !state.busy) { Text(if (state.busy) "Testing…" else "Run test") }
            OutlinedButton(onClick = { vm.inventoryOnce() }) { Text("Log inventory") }
        }
    }
}

@SuppressLint("BatteryLife")
internal fun requestUnrestrictedBattery(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )
}

internal fun formatTime(ms: Long): String =
    if (ms <= 0) "—" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

// ---- 5. Log -------------------------------------------------------------------------------

@Composable
internal fun LogCard() {
    val lines by DiagLog.lines.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun withReport(action: (String) -> Unit) {
        building = true
        result = null
        scope.launch {
            val text = runCatching { ReportBuilder.build(context) }.getOrElse { "Report failed: ${it.message}" }
            building = false
            action(text)
        }
    }

    Section("Diagnostics", "Everything needed to explain what the miner did") {
        Hint("The full report includes settings, power state, the miner's internals, a 24-hour uptime table, every mining minute for the last 6 hours, a live inventory snapshot, how Android last stopped the app, and the persistent log. It contains no tokens.")
        Buttons {
            Button(onClick = {
                withReport { text ->
                    val file = ReportBuilder.writeFile(context, text)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, file.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share PocketDrop report"))
                    result = "Report ready: ${file.name} (${text.length / 1024} KB)"
                    DiagLog.i("report: shared ${file.name}, ${text.length / 1024} KB")
                }
            }, enabled = !building) { Text(if (building) "Building…" else "Share full report") }
            OutlinedButton(onClick = {
                withReport { text ->
                    // Clipboard transfers fail above ~1 MB; keep the head (all the state) and the newest log.
                    val limit = 400_000
                    val clipped = if (text.length <= limit) text else
                        text.take(limit / 2) + "\n\n…[${(text.length - limit) / 1024} KB of older log cut; use Share for everything]…\n\n" + text.takeLast(limit / 2)
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PocketDrop report", clipped))) }
                    result = "Copied ${clipped.length / 1024} KB" + if (clipped.length < text.length) " (trimmed; Share has it all)" else ""
                }
            }, enabled = !building) { Text("Copy full report") }
            TextButton(onClick = { DiagLog.clear(); result = "Log cleared" }) { Text("Clear log") }
        }
        result?.let { Hint(it) }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    if (lines.isEmpty()) "Nothing logged yet." else lines.takeLast(80).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
