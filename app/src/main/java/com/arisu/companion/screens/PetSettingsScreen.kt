package com.arisu.companion.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    val sulking by store.sulkingEnabled.collectAsState()
    val idleTimeout by store.idleTimeoutSeconds.collectAsState()
    val idleBubble by store.idleSpeechBubble.collectAsState()
    val animSpeed by store.animationSpeed.collectAsState()
    val reducedMotion by store.reducedMotion.collectAsState()
    val quietOn by store.quietHoursEnabled.collectAsState()
    val quietStart by store.quietStartHour.collectAsState()
    val quietEnd by store.quietEndHour.collectAsState()
    val debugOverlay by store.debugOverlay.collectAsState()
    val activePet by activity.container.petLibrary.active.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Karakter & perilaku", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Karakter aktif: ${activePet.displayName}. Ganti model, impor karakter sendiri, dan atur " +
                "pemetaan animasinya di tab Model.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Panel {
            LabeledSlider(
                label = "Ukuran karakter",
                value = scale,
                range = 0.4f..2.2f,
                format = { "%.1f×".format(it) },
            ) { store.savePetScale(it) }
            Button(onClick = { store.resetPetPosition() }) { Text("Reset posisi tersimpan") }
            Text(
                "Posisi diingat antar-restart. Geser karakternya di overlay untuk memindahkan; " +
                    "tekan lama untuk menu cepat (besar/kecil, reset, sembunyikan).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel {
            ToggleRow(
                label = "Dempet ke tepi layar",
                checked = snap,
                onChange = { store.saveSnapToEdge(it) },
                supporting = "Setelah digeser, karakter menempel ke tepi terdekat.",
            )
            ToggleRow(
                label = "Tap membuka panel chat",
                checked = tapChat,
                onChange = { store.saveTapOpensChat(it) },
                supporting = "Kalau dimatikan, tap hanya melambai (animasi reaksi).",
            )
        }

        Text("Saat diabaikan", style = MaterialTheme.typography.titleMedium)
        Panel {
            ToggleRow(
                label = "Ngambek setelah diabaikan",
                checked = sulking,
                onChange = { store.saveSulkingEnabled(it) },
                supporting = "Sekali per periode diam — tidak berulang tiap menit, tanpa panggilan API.",
            )
            LabeledSlider(
                label = "Batas diam",
                value = idleTimeout.toFloat(),
                range = 10f..300f,
                format = { "%.0f detik".format(it) },
            ) { store.saveIdleTimeoutSeconds(it.toInt()) }
            ToggleRow(
                label = "Balon ucapan saat ngambek",
                checked = idleBubble,
                onChange = { store.saveIdleSpeechBubble(it) },
                supporting = "Pesan lokal dari karakter, misalnya \"Arisu dicuekin lagi...\".",
            )
        }

        Text("Animasi & performa", style = MaterialTheme.typography.titleMedium)
        Panel {
            LabeledSlider(
                label = "Kecepatan animasi",
                value = animSpeed,
                range = 0.25f..3f,
                format = { "%.2f×".format(it) },
            ) { store.saveAnimationSpeed(it) }
            LabeledSlider(
                label = "Batas FPS render",
                value = fps.toFloat(),
                range = 15f..60f,
                format = { "%.0f fps".format(it) },
            ) { store.saveFpsLimit(it.toInt()) }
            ToggleRow(
                label = "Reduced motion",
                checked = reducedMotion,
                onChange = { store.saveReducedMotion(it) },
                supporting = "Mematikan goyangan dan putaran prosedural (animasi clip tetap jalan).",
            )
            ToggleRow(
                label = "Overlay debug animasi",
                checked = debugOverlay,
                onChange = { store.saveDebugOverlay(it) },
                supporting = "Menampilkan clip aktif, waktu animasi, delta frame dan FPS.",
            )
        }

        Text("Jam tenang", style = MaterialTheme.typography.titleMedium)
        Panel {
            ToggleRow(
                label = "Aktifkan jam tenang",
                checked = quietOn,
                onChange = { store.saveQuietHoursEnabled(it) },
                supporting = "Karakter tidak akan ngambek pada jam ini.",
            )
            LabeledSlider(
                label = "Mulai",
                value = quietStart.toFloat(),
                range = 0f..23f,
                format = { "%02.0f:00".format(it) },
            ) { store.saveQuietWindow(it.toInt(), quietEnd) }
            LabeledSlider(
                label = "Selesai",
                value = quietEnd.toFloat(),
                range = 0f..23f,
                format = { "%02.0f:00".format(it) },
            ) { store.saveQuietWindow(quietStart, it.toInt()) }
        }

        Panel {
            ToggleRow(
                label = "Jalankan otomatis setelah reboot",
                checked = autoStart,
                onChange = { store.saveAutoStartOnBoot(it) },
                supporting = "Hanya jalan di perangkat yang mengizinkan start otomatis.",
            )
        }
    }
}
