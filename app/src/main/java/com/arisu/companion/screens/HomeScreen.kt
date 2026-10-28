package com.arisu.companion.screens

import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arisu.companion.MainActivity
import com.arisu.companion.renderer.ArisuRenderer
import com.arisu.companion.ui.Panel

@Composable
fun HomeScreen(
    activity: MainActivity,
    onStartPet: () -> Unit,
    onStopPet: () -> Unit,
) {
    val appState = activity.container.appState
    val profile = appState.activeProfile()
    val scale by appState.petScale.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Arisu 🎀", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your floating 3D companion. She sits above other apps — drag her, tap her, " +
                "double-tap her and chat with her.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- character preview
        Panel {
            Text("Character preview", style = MaterialTheme.typography.titleMedium)
            if (android.provider.Settings.canDrawOverlays(activity)) {
                var preview by remember { mutableStateOf<ArisuRenderer?>(null) }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.45f)
                        .padding(vertical = 8.dp),
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            preview = ArisuRenderer(tv)
                        }
                    },
                    update = { },
                )
                DisposableEffect(Unit) {
                    onDispose {
                        preview?.destroy()
                        preview = null
                    }
                }
                // Kick the model load once the renderer exists.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val bytes = activity.assets.open("Arisu (Maid).glb").use { a -> a.readBytes() }
                    preview?.loadModel(bytes)
                }
            } else {
                Text(
                    "Grant the \"Display over other apps\" permission to see the live preview " +
                        "and float Arisu above other apps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview size", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text("%.1f×".format(scale))
            }
            Slider(
                value = scale,
                onValueChange = { appState.savePetScale(it) },
                valueRange = 0.4f..2.2f,
            )
        }

        // ---- floating pet controls
        Panel {
            Text("Floating pet", style = MaterialTheme.typography.titleMedium)
            val overlayOk = android.provider.Settings.canDrawOverlays(activity)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (!overlayOk) activity.requestOverlayPermission() else onStartPet()
                }) {
                    Text(if (overlayOk) "Start Floating Pet" else "Grant overlay permission")
                }
                OutlinedButton(onClick = onStopPet) { Text("Stop") }
            }
            Text(
                if (overlayOk) "Overlay permission granted ✔" else "Starting the pet needs the overlay permission switch ON.",
                style = MaterialTheme.typography.labelMedium,
                color = if (overlayOk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
        }

        // ---- AI provider status
        Panel {
            Text("AI provider", style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text(profile?.name ?: "Not configured") },
                overlineContent = { Text("Active provider") },
                supportingContent = {
                    val keyMissing = profile != null &&
                        activity.container.appState.decryptKey(profile.apiKeyCipher) == null
                    Text(
                        when {
                            profile == null -> "Open the AI tab to add a key."
                            profile.modelId.isBlank() -> "No model set — pick one in the AI tab."
                            keyMissing -> "Model: " + profile.modelId + " · API key missing"
                            else -> "Model: " + profile.modelId
                        },
                    )
                },
            )
        }
    }
}
