@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dropspike.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Games("Games", AppIcons.Gamepad),
    Rewards("Rewards", AppIcons.Trophy),
    Settings("Settings", Icons.Default.Settings),
}

/** Root: onboarding until signed in, then Home / Games / Rewards / Settings. */
@Composable
fun PocketDropApp(vm: MainViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (account.login == null) OnboardingScreen(vm) else MainShell(vm)
    }
    // On-screen token mints (Settings → Advanced) show their WebView in a dialog.
    MintOverlay(vm)
}

@Composable
private fun MainShell(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val haptics = rememberHaptics()
    // Bottom bar on the folded phone, navigation rail when unfolded: chosen by window size.
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { t ->
                item(
                    selected = tab == t,
                    onClick = {
                        if (tab != t) haptics.tick()
                        tab = t
                    },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(t.label) },
                )
            }
        },
    ) {
        // Shared-axis style: the new tab rises in slightly as the old one fades out.
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(MotionTokens.MEDIUM, delayMillis = 60)) + slideInVertically(tween(MotionTokens.MEDIUM)) { it / 20 }) togetherWith
                    fadeOut(tween(MotionTokens.SHORT - 60))
            },
            label = "tab",
        ) { current ->
            when (current) {
                Tab.Home -> HomeScreen(vm, onOpenGames = { tab = Tab.Games })
                Tab.Games -> GamesScreen(vm)
                Tab.Rewards -> RewardsScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

/**
 * A centred, width-limited scrolling column: readable on the unfolded Fold, full-width folded.
 * With [onRefresh], pulling down refreshes.
 */
@Composable
internal fun ScreenList(
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val list = @Composable {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = state,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
    if (onRefresh == null) {
        list()
    } else {
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
                )
            },
        ) { list() }
    }
}

/** Notification permission and battery exemption, re-checked whenever the app resumes. */
internal class PermissionState(
    val notificationsOk: Boolean,
    val batteryOk: Boolean,
    val requestNotifications: () -> Unit,
    val requestBattery: () -> Unit,
)

@Composable
internal fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    val notificationsOk = remember(resumes) { notificationsAllowed(context) }
    val batteryOk = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    return PermissionState(
        notificationsOk = notificationsOk,
        batteryOk = batteryOk,
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        requestBattery = { requestUnrestrictedBattery(context) },
    )
}

private fun notificationsAllowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
