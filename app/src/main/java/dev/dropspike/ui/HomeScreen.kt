@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dropspike.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dropspike.BuildConfigInfo
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import dev.dropspike.service.MinerService
import dev.dropspike.service.MinerState
import dev.dropspike.twitch.DashboardResult
import dev.dropspike.twitch.MintPage
import dev.dropspike.twitch.MintSurface
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: MainViewModel) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DropSpike", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Spike: login · integrity · background",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        // Single centred column; on the unfolded Fold it stays readable instead of stretching.
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { AccountCard(vm) }
                item { IntegrityCard(vm) }
                item { GateCard(vm) }
                item { BackgroundCard(vm) }
                item { LogCard() }
            }
        }
    }
}

@Composable
private fun Section(step: Int, title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$step", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            content()
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (ok) {
            true -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E9D5B), modifier = Modifier.size(18.dp))
            false -> Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            null -> Box(Modifier.size(18.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

// ---- 1. Account ---------------------------------------------------------------------------

@Composable
private fun AccountCard(vm: MainViewModel) {
    val state by vm.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pasting by remember { mutableStateOf(false) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.onTokenStored()
    }

    Section(1, "Twitch account") {
        when {
            state.busy -> StatusLine(null, "Checking token…")
            state.login != null -> {
                StatusLine(true, "Signed in as ${state.login}")
                Hint("Token client ID: ${state.clientId}")
            }
            else -> StatusLine(false, state.error ?: "Not signed in")
        }
        Buttons {
            if (state.login == null) {
                Button(onClick = { login.launch(Intent(context, LoginActivity::class.java)) }) { Text("Sign in") }
                OutlinedButton(onClick = { pasting = true }) { Text("Paste token") }
            } else {
                OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
            }
        }
        if (state.login == null) Hint("Sign-in uses Twitch's own page; the app only keeps the session cookie. \"Paste token\" takes the auth-token cookie from a desktop browser if the in-app page is blocked.")
    }

    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("Paste auth-token") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("auth-token cookie value") })
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { pasting = false; vm.pasteToken(text) }) { Text("Use token") }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("Cancel") } },
        )
    }
}

// ---- 2. Integrity -------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun IntegrityCard(vm: MainViewModel) {
    val state by vm.integrity.collectAsStateWithLifecycle()

    Section(2, "Integrity token") {
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

        // On-screen mint: the WebView is part of the layout while it works.
        val pending = state.pendingVisibleMint
        if (pending != null) {
            var webView by remember(pending) { mutableStateOf<WebView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                factory = { ctx -> WebView(ctx).also { webView = it } },
                onRelease = { it.destroy() },
            )
            LaunchedEffect(pending, webView) {
                webView?.let { vm.runMint(it) }
            }
        }

        Buttons {
            FilledTonalButton(onClick = vm::mint, enabled = !state.busy) { Text("Mint token") }
        }
    }
}

// ---- 3. Gate test -------------------------------------------------------------------------

@Composable
private fun GateCard(vm: MainViewModel) {
    val state by vm.gate.collectAsStateWithLifecycle()

    Section(3, "Campaign gate test") {
        Hint("Requests the drops dashboard twice. Since Sep 18 Twitch returns null without a valid integrity token. A campaign list on the second row means the approach works.")
        GateRow("Without token", state.without, state.withoutError, expectOk = false)
        GateRow("With token", state.with, state.withError, expectOk = true)
        state.with?.campaigns?.takeIf { it.isNotEmpty() }?.let { campaigns ->
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

@Composable
private fun GateRow(label: String, result: DashboardResult?, error: String?, expectOk: Boolean) {
    val text = when {
        error != null -> "$label: $error"
        result == null -> "$label: not run"
        else -> "$label: ${MainViewModel.describe(result)}"
    }
    val ok = when {
        result == null && error == null -> null
        expectOk -> result?.campaigns != null
        // Without a token, "null" is the expected (gated) answer; a list means the gate is gone.
        else -> null
    }
    StatusLine(ok, text)
}

// ---- 4. Background ------------------------------------------------------------------------

@Composable
private fun BackgroundCard(vm: MainViewModel) {
    val context = LocalContext.current
    val status by MinerState.status.collectAsStateWithLifecycle()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    val notificationsOk = remember(resumes) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val prefs = DropSpikeApp.instance.prefs
    val killed = !status.running && prefs.sessionActive

    Section(4, "Background session") {
        Hint("A foreground service that ticks once a minute (the cadence watch heartbeats need) and checks drop progress every 5 minutes. Start it, turn the screen off for an hour, then compare ticks with elapsed time.")
        StatusLine(notificationsOk, if (notificationsOk) "Notifications allowed" else "Notifications blocked")
        StatusLine(batteryOk, if (batteryOk) "Battery: unrestricted" else "Battery: optimised (Android may pause the session)")

        if (killed) {
            StatusLine(false, "Previous session ended without Stop. Last tick ${formatTime(prefs.lastTickAt)}")
        }
        if (status.running || status.ticks > 0) {
            StatusLine(
                if (status.running) true else null,
                if (status.running) "Running · ${status.ticks} ticks since ${formatTime(prefs.sessionStartedAt)}" else "Stopped after ${status.ticks} ticks",
            )
            Hint("Largest gap between ticks: ${status.maxGapSec}s (≈60s is healthy)")
            if (status.summary.isNotEmpty()) Hint(status.summary)
        }

        Buttons {
            if (status.running) {
                Button(onClick = { MinerService.stop(context) }) { Text("Stop") }
            } else {
                Button(onClick = {
                    if (!notificationsOk && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    prefs.sessionActive = false
                    MinerService.start(context)
                }) { Text("Start") }
            }
            if (!batteryOk) OutlinedButton(onClick = { requestUnrestrictedBattery(context) }) { Text("Allow background") }
        }
    }
}

@SuppressLint("BatteryLife")
private fun requestUnrestrictedBattery(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )
}

private fun formatTime(ms: Long): String =
    if (ms <= 0) "—" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

// ---- 5. Log -------------------------------------------------------------------------------

@Composable
private fun LogCard() {
    val lines by DiagLog.lines.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Section(5, "Diagnostics") {
        Hint("Contains no tokens. Copy and share this when reporting results.")
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    if (lines.isEmpty()) "Nothing logged yet." else lines.takeLast(60).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Buttons {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(BuildConfigInfo.header(context) + lines.joinToString("\n"))) }) {
                Text("Copy report")
            }
            TextButton(onClick = DiagLog::clear) { Text("Clear") }
        }
    }
}
