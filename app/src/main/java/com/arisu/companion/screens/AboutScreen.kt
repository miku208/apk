package com.arisu.companion.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arisu.companion.BuildConfig
import com.arisu.companion.renderer.GlbInfo
import com.arisu.companion.renderer.GlbParser
import com.arisu.companion.ui.Panel

@Composable
fun AboutScreen() {
    var glbInfo by remember { mutableStateOf<GlbInfo?>(null) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("About", style = MaterialTheme.typography.headlineSmall)

        Panel {
            Text("Arisu Companion", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version " + BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "A floating 3D AI companion for Android 10+. She lives in an overlay window " +
                    "you can drag, scale and chat with. Local-first: chat history and memories " +
                    "stay on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            Text("Permissions & why", style = MaterialTheme.typography.titleMedium)
            PermissionRow(
                "Display over other apps",
                "Required for the floating 3D pet. Granted explicitly through Android Settings; the app never enables it silently.",
            )
            PermissionRow(
                "Notifications",
                "The foreground service notification keeps the system satisfied and offers quick hide/stop actions.",
            )
            PermissionRow(
                "Microphone",
                "Requested only when you enable voice mode; push-to-talk uses the on-device SpeechRecognizer.",
            )
            PermissionRow(
                "Internet",
                "Direct requests to the AI provider you configure (OpenAI-compatible chat format). No key goes anywhere else.",
            )
            PermissionRow(
                "Start after reboot",
                "Only restores the pet if you enabled auto-start and the system permits it.",
            )
            Text(
                "Overlays are restricted on some system screens (lock screen, special apps); " +
                    "she pauses rendering there — that is expected platform behavior.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            Text("Model diagnostics", style = MaterialTheme.typography.titleMedium)
            LaunchedEffect(Unit) {
                try {
                    val bytes = context.assets.open("Arisu (Maid).glb").use { it.readBytes() }
                    glbInfo = GlbParser.parse(bytes)
                } catch (e: Exception) {
                    glbInfo = null
                }
            }
            val info = glbInfo
            if (info != null) {
                Text("Asset: Arisu (Maid).glb — ${info.totalBytes / 1024 / 1024} MB")
                Text(
                    "Animations (${info.animations.size}): " +
                        info.animations.take(6).joinToString(", ") +
                        if (info.animations.size > 6) "…" else "",
                )
                Text(
                    "Meshes: ${info.meshCount} · Materials: ${info.materialCount} · " +
                        "Skins: ${info.skinCount} · Textures: ${info.imageCount}",
                )
                Text(
                    if (info.hasEmbeddedTextures) "Textures embedded in the GLB ✔" else "Textures referenced externally",
                )
            } else {
                Text(
                    "GLB asset missing or unreadable.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Panel {
            Text("Open-source licenses", style = MaterialTheme.typography.titleMedium)
            Text(
                "Arisu Companion app code: MIT.\n" +
                    "Filament / gltfio (Apache License 2.0) © Google, Inc.\n" +
                    "Jetpack Compose, Room, OkHttp, Kotlin coroutines & serialization (Apache License 2.0).\n\n" +
                    "The character asset \"Arisu (Maid).glb\" is provided by the user and remains the " +
                    "property of its original authors (Blue Archive). It is not covered by the app's " +
                    "license — distribute it only in accordance with their terms.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, why: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
