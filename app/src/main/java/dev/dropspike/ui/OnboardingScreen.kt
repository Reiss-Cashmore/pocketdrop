package dev.dropspike.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** First run: what PocketDrop does, and the ways to sign in. */
@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var codeLogin by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.onTokenStored()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.background, scheme.background))),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .systemBarsPadding()
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            // The logo drops in with a little bounce, then floats gently.
            val drop = remember { Animatable(0f) }
            LaunchedEffect(Unit) { drop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 180f)) }
            val float by rememberInfiniteTransition(label = "float").animateFloat(
                initialValue = -4f,
                targetValue = 4f,
                animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "floatY",
            )
            AppLogo(
                112.dp,
                Modifier.graphicsLayer {
                    translationY = (1f - drop.value) * -120.dp.toPx() + float.dp.toPx()
                    alpha = drop.value.coerceIn(0f, 1f)
                },
            )
            Text("PocketDrop", style = MaterialTheme.typography.displaySmall, modifier = Modifier.enterStagger(2))
            Text(
                "Earn Twitch drops in the background. No video, barely any battery.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.enterStagger(3),
            )
            Spacer(Modifier.height(8.dp))
            Feature(AppIcons.Bolt, "Watches for you", "Sends Twitch the same once-a-minute signal its player does, without streaming anything.", Modifier.enterStagger(4))
            Feature(AppIcons.Gamepad, "Your games first", "Pick games and PocketDrop mines them the moment they go live, even overnight.", Modifier.enterStagger(5))
            Feature(Icons.Default.CheckCircle, "Claims automatically", "Finished drops are claimed as soon as they're ready.", Modifier.enterStagger(6))
            Spacer(Modifier.height(8.dp))

            if (account.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Signing in…")
                }
            }
            account.error?.let { StatusLine(false, it) }

            val signIn = remember { MutableInteractionSource() }
            Button(
                onClick = { login.launch(Intent(context, LoginActivity::class.java)) },
                enabled = !account.busy,
                interactionSource = signIn,
                modifier = Modifier.fillMaxWidth().height(56.dp).enterStagger(7).pressScale(signIn),
            ) { Text("Sign in with Twitch", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(
                onClick = { codeLogin = true },
                enabled = !account.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp).enterStagger(8),
            ) { Text("Use a code instead") }
            TextButton(onClick = { pasting = true }, enabled = !account.busy) { Text("Paste a session token") }
            Hint(
                "PocketDrop never sees your password; Twitch's own sign-in page hands it a session. Not affiliated with Twitch, and automating it may break Twitch's terms.",
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }

    if (codeLogin) DeviceLoginDialog(vm, onDismiss = { codeLogin = false; vm.cancelDeviceLogin() })
    if (pasting) PasteTokenDialog(onDismiss = { pasting = false }, onToken = { pasting = false; vm.pasteToken(it) })
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PasteTokenDialog(onDismiss: () -> Unit, onToken: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a session token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint("On a computer signed in to twitch.tv, open developer tools → cookies for twitch.tv and copy the value of auth-token.")
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("auth-token") })
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onToken(text) }) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
