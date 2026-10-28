package com.arisu.companion.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arisu.companion.MainActivity
import com.arisu.companion.ui.LabeledSlider
import com.arisu.companion.ui.Panel
import com.arisu.companion.ui.ToggleRow

@Composable
fun PetSettingsScreen(activity: MainActivity) {
    val store = activity.container.appState
    val scale by store.petScale.collectAsState()
    val fps by store.fpsLimit.collectAsState()
    val snap by store.snapToEdge.collectAsState()
    val tapChat by store.tapOpensChat.collectAsState()
    val autoStart by store.autoStartOnBoot.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pet look & feel", style = MaterialTheme.typography.headlineSmall)

        Panel {
            LabeledSlider(
                label = "Character scale",
                value = scale,
                range = 0.4f..2.2f,
                format = { "%.1f×".format(it) },
            ) { store.savePetScale(it) }
            Button(onClick = {
                store.resetPetPosition()
                val intent = android.content.Intent(
                    activity,
                    com.arisu.companion.overlay.FloatingPetService::class.java,
                ).also { it.action = com.arisu.companion.overlay.FloatingPetService.ACTION_STOP }
            }) { Text("Reset saved position") }
            Text(
                "Position is remembered across restarts. Drag her in the overlay to move; " +
                    "long-press her for the quick menu (bigger/smaller, reset, hide).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            ToggleRow(
                label = "Snap to screen edge",
                checked = snap,
                onChange = { store.saveSnapToEdge(it) },
                supporting = "After a drag, she gently docks to the nearest horizontal edge.",
            )
            ToggleRow(
                label = "Tap opens chat panel",
                checked = tapChat,
                onChange = { store.saveTapOpensChat(it) },
                supporting = "Off: single tap waves instead of opening chat.",
            )
        }

        Panel {
            LabeledSlider(
                label = "Render FPS limit",
                value = fps.toFloat(),
                range = 15f..60f,
                format = { "%.0f fps".format(it) },
            ) { store.saveFpsLimit(it.toInt()) }
            Text(
                "Lower FPS saves battery on mid-range phones while dragging still stays smooth.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            ToggleRow(
                label = "Auto-start after reboot",
                checked = autoStart,
                onChange = { store.saveAutoStartOnBoot(it) },
                supporting = "Works only where the system allows background starts after restart.",
            )
        }
    }
}
