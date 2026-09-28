package dev.dropspike.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            DropSpikeTheme(dynamicColor = dynamic, themeMode = themeMode) { PocketDropApp(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.autoMineCheck("app opened")
    }
}
