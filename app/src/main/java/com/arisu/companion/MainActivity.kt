package com.arisu.companion

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.arisu.companion.overlay.FloatingPetService
import com.arisu.companion.screens.AboutScreen
import com.arisu.companion.screens.AiSettingsScreen
import com.arisu.companion.screens.HomeScreen
import com.arisu.companion.screens.PetLibraryScreen
import com.arisu.companion.screens.PetSettingsScreen
import com.arisu.companion.screens.PersonalityScreen
import com.arisu.companion.ui.ArisuTheme

class MainActivity : ComponentActivity() {

    val container by lazy { (application as ArisuApplication).container }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) container.voiceController.maybeInit()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun requestMicPermission() {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startFloatingPet() {
        requestNotificationPermission()
        val intent = Intent(this, FloatingPetService::class.java)
        startForegroundService(intent)
    }

    fun stopFloatingPet() {
        stopService(Intent(this, FloatingPetService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startTab = intent?.getStringExtra("open_tab") ?: "home"
        setContent {
            ArisuTheme {
                ArisuRoot(startTab)
            }
        }
    }

    @Composable
    private fun ArisuRoot(initialTab: String) {
        var tab by remember { mutableStateOf(initialTab) }
        val snackbar = remember { SnackbarHostState() }

        data class TabItem(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)
        val items = listOf(
            TabItem("home", Icons.Rounded.Home, "Arisu"),
            TabItem("ai", Icons.Rounded.Chat, "AI"),
            TabItem("pet", Icons.Rounded.Face, "Pet"),
            TabItem("library", Icons.Rounded.Pets, "Model"),
            TabItem("persona", Icons.Rounded.Psychology, "Persona"),
            TabItem("history", Icons.Rounded.Settings, "History"),
            TabItem("about", Icons.Rounded.Info, "About"),
        )

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item.route,
                            onClick = { tab = item.route },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                when (tab) {
                    "home" -> HomeScreen(
                        activity = this@MainActivity,
                        onStartPet = { startFloatingPet() },
                        onStopPet = { stopFloatingPet() },
                    )
                    "ai" -> AiSettingsScreen(activity = this@MainActivity)
                    "pet" -> PetSettingsScreen(activity = this@MainActivity)
                    "library" -> PetLibraryScreen(activity = this@MainActivity)
                    "persona" -> PersonalityScreen(activity = this@MainActivity)
                    "history" -> com.arisu.companion.screens.ChatHistoryScreen(activity = this@MainActivity)
                    else -> AboutScreen()
                }
            }
        }
    }
}
