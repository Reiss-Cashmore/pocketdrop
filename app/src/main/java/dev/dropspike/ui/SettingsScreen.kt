@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dropspike.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ExperimentalMaterial3Api
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

private val THEMES = listOf("system" to "System", "light" to "Light", "dark" to "Dark")

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
    val integrity by vm.integrity.collectAsStateWithLifecycle()
    val mining by vm.mining.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val perms = rememberPermissionState()
    val context = LocalContext.current
    var advanced by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()

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
        item {
            Section("Mining", "When and how PocketDrop watches", icon = AppIcons.Drop) {
                SettingRow("Only while charging", "Pause mining when the phone is unplugged", icon = AppIcons.Bolt) {
                    Switch(checked = mining.onlyCharging, onCheckedChange = { haptics.tick(); vm.setOnlyCharging(it) })
                }
                SettingRow("Only on Wi-Fi", "Pause mining on mobile data (it uses very little: no video is streamed)", icon = AppIcons.Wifi) {
                    Switch(checked = mining.onlyWifi, onCheckedChange = { haptics.tick(); vm.setOnlyWifi(it) })
                }
                SettingRow("Watch two channels", "Mine two games at once, like a second browser tab", icon = AppIcons.Gamepad) {
                    Switch(checked = mining.twoChannels, onCheckedChange = { haptics.tick(); vm.setTwoChannels(it) })
                }
                if (mining.twoChannels) {
                    StatusLine(false, "Experimental: Twitch may not credit both channels every minute, and sometimes credits only one. Check the uptime chart; turn this off if progress slows.")
                }
            }
        }
        item {
            Section("Appearance", icon = Icons.Default.Star) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    THEMES.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { haptics.tick(); vm.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, THEMES.size),
                        ) { Text(label) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingRow("Wallpaper colours", "Use Material You colours instead of PocketDrop purple") {
                        Switch(checked = dynamic, onCheckedChange = { haptics.tick(); vm.setDynamicColor(it) })
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
            item(key = "integrity") { Box(Modifier.animateItem()) { IntegrityCard(vm) } }
            item(key = "gate") { Box(Modifier.animateItem()) { GateCard(vm) } }
        }
        item {
            Section("Security", icon = Icons.Default.Lock) {
                SettingRow("Sign-in", "Your Twitch tokens are encrypted with a key held in Android's secure hardware", icon = Icons.Default.Lock)
                val publicKey = remember { SystemInfo.signedWithPublicKey(context) }
                if (publicKey) {
                    StatusLine(false, "This build is signed with the public test key from the repo. Anyone could publish an update that installs over it. Add a private key (README, Signing).")
                } else {
                    StatusLine(true, "Signed with your private key")
                }
            }
        }
        item {
            Section("About", icon = Icons.Default.Info) {
                SettingRow("PocketDrop ${SystemInfo.appVersion(context)}", "Mining logic ported from DropForge (MIT) and Streamlink (BSD-2-Clause). Not affiliated with Twitch. Automating Twitch may break its terms; use at your own risk.")
            }
        }
    }
}
