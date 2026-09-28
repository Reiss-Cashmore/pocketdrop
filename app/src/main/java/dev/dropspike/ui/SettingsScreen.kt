package dev.dropspike.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dropspike.data.Prefs
import dev.dropspike.data.SystemInfo

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    val integrity by vm.integrity.collectAsStateWithLifecycle()
    val perms = rememberPermissionState()
    val context = LocalContext.current
    var advanced by rememberSaveable { mutableStateOf(false) }

    ScreenList {
        item { ScreenHeader("Settings") }
        item {
            Section("Account", icon = Icons.Default.Person) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(account.login?.take(1)?.uppercase().orEmpty(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.login.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Hint(if (account.clientId == Prefs.WEB_CLIENT_ID) "Twitch web session · full campaign list" else "Code sign-in · campaign list limited")
                    }
                    OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
                }
                val tokenOk = integrity.expiresAt > System.currentTimeMillis()
                SettingRow(
                    "Campaign access",
                    if (tokenOk) "Unlocked until ${formatTime(integrity.expiresAt)}; renewed automatically" else "Renewed automatically when needed",
                    icon = Icons.Default.CheckCircle,
                )
            }
        }
        item {
            Section("Background", "What Android needs to let mining run with the screen off", icon = Icons.Default.Notifications) {
                SettingRow(
                    "Notifications",
                    if (perms.notificationsOk) "Allowed" else "Needed for the mining notification",
                    icon = Icons.Default.Notifications,
                ) {
                    if (!perms.notificationsOk) FilledTonalButton(onClick = perms.requestNotifications) { Text("Allow") }
                    else Icon(Icons.Default.CheckCircle, "Allowed", tint = StatusColors.good)
                }
                SettingRow(
                    "Battery",
                    if (perms.batteryOk) "Unrestricted" else "Optimised: Android may pause mining",
                    icon = AppIcons.Bolt,
                ) {
                    if (!perms.batteryOk) FilledTonalButton(onClick = perms.requestBattery) { Text("Allow") }
                    else Icon(Icons.Default.CheckCircle, "Unrestricted", tint = StatusColors.good)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                Section("Appearance", icon = Icons.Default.Star) {
                    SettingRow("Wallpaper colours", "Use Material You colours instead of PocketDrop purple") {
                        Switch(checked = dynamic, onCheckedChange = vm::setDynamicColor)
                    }
                }
            }
        }
        item { LogCard() }
        item {
            Section("Advanced", "Manual token minting and the campaign gate test", icon = Icons.Default.Build) {
                SettingRow(
                    if (advanced) "Hide advanced tools" else "Show advanced tools",
                    "Only needed when something isn't working",
                    onClick = { advanced = !advanced },
                ) {
                    Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }
        }
        if (advanced) {
            item { IntegrityCard(vm) }
            item { GateCard(vm) }
        }
        item {
            Section("About", icon = Icons.Default.Info) {
                SettingRow("PocketDrop ${SystemInfo.appVersion(context)}", "Mining logic ported from DropForge (MIT) and Streamlink (BSD-2-Clause). Not affiliated with Twitch. Automating Twitch may break its terms; use at your own risk.")
            }
        }
    }
}
