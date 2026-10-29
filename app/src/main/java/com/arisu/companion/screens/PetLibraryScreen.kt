package com.arisu.companion.screens

import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import com.arisu.companion.MainActivity
import com.arisu.companion.animation.PetState
import com.arisu.companion.pet.PetLibrary
import com.arisu.companion.pet.PetProfile
import com.arisu.companion.renderer.ArisuRenderer
import com.arisu.companion.ui.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pet Library: installed 3D characters, model import through the Storage Access
 * Framework, a live preview, activation, rename/delete, per-character animation
 * mapping and per-character personality.
 */
@Composable
fun PetLibraryScreen(activity: MainActivity) {
    val library = activity.container.petLibrary
    val profiles by library.profiles.collectAsState()
    val activeId by library.activeId.collectAsState()
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<PetProfile?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        status = "Mengimpor model..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { library.importFromUri(uri) }
            importing = false
            when (result) {
                is PetLibrary.ImportResult.Success -> {
                    selectedId = result.profile.id
                    status = "Berhasil mengimpor \"${result.profile.displayName}\" " +
                        "(${result.profile.clipNames.size} animasi)."
                }
                is PetLibrary.ImportResult.Failure -> status = "Gagal: ${result.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pet Library", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Impor karakter 3D milikmu sendiri. File disalin ke penyimpanan privat aplikasi, " +
                "jadi tetap jalan walau file aslinya dihapus. Animasi tiap karakter dipetakan sendiri.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                    Text(if (importing) "Mengimpor..." else "Impor model")
                }
                Text("GLB / ZIP (glTF + aset)", style = MaterialTheme.typography.labelSmall)
            }
            if (importing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            Text(
                "Didukung: .glb, atau .zip berisi satu .gltf beserta .bin/tekstur. " +
                    "Maksimum ${PetLibrary.MAX_MB} MB. Model tanpa animasi tetap bisa dipakai (pose statis).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it.startsWith("Gagal")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        profiles.forEach { profile ->
            PetCard(
                profile = profile,
                library = library,
                active = profile.id == activeId,
                expanded = profile.id == selectedId,
                onSelect = { selectedId = if (selectedId == profile.id) null else profile.id },
                onActivate = {
                    library.setActive(profile.id)
                    status = "\"${profile.displayName}\" sekarang aktif."
                },
                onDelete = { deleteTarget = profile },
            )
        }

        profiles.firstOrNull { it.id == selectedId }?.let { profile ->
            PetEditor(
                profile = profile,
                library = library,
                onChanged = { status = "Tersimpan." },
            )
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus \"${target.displayName}\"?") },
            text = { Text("Model dan pengaturannya dihapus dari perangkat. Riwayat chat-nya tetap disimpan.") },
            confirmButton = {
                TextButton(onClick = {
                    if (library.delete(target.id)) {
                        if (selectedId == target.id) selectedId = null
                        status = "\"${target.displayName}\" dihapus."
                    } else {
                        status = "Karakter bawaan tidak bisa dihapus."
                    }
                    deleteTarget = null
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun PetCard(
    profile: PetProfile,
    library: PetLibrary,
    active: Boolean,
    expanded: Boolean,
    onSelect: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (active) 6.dp else 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(if (profile.bundled) "Bawaan" else "Impor")
                            append(" · ${profile.clipNames.size} animasi")
                            if (profile.modelBytes > 0) {
                                append(" · ").append("%.1f MB".format(profile.modelBytes / 1048576f))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) {
                    AssistChip(onClick = {}, label = { Text("Aktif") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onClick = onSelect) { Text(if (expanded) "Tutup" else "Kelola") }
                if (!active) TextButton(onClick = onActivate) { Text("Aktifkan") }
                if (!profile.bundled) {
                    TextButton(onClick = onDelete) {
                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (expanded) {
                PetPreview(profile, library)
            }
        }
    }
}

/** Live 3D preview of the selected character, before it is activated. */
@Composable
private fun PetPreview(profile: PetProfile, library: PetLibrary) {
    val bytes by produceState<ByteArray?>(initialValue = null, key1 = profile.id) {
        value = withContext(Dispatchers.IO) { library.modelBytes(profile) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val data = bytes
        if (data == null) {
            CircularProgressIndicator(Modifier.size(28.dp))
        } else {
            key(profile.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val container = FrameLayout(ctx)
                        val texture = TextureView(ctx)
                        container.addView(
                            texture,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        val renderer = ArisuRenderer(textureView = texture)
                        renderer.setFpsLimit(30)
                        renderer.setAnimationOverrides(profile.overridesAsStates())
                        renderer.setAnimationSpeed(profile.animationSpeed)
                        runCatching { renderer.loadModel(data) }
                        container.tag = renderer
                        container
                    },
                    onRelease = { container ->
                        (container.tag as? ArisuRenderer)?.destroy()
                        container.tag = null
                    },
                )
            }
        }
    }
}

@Composable
private fun PetEditor(
    profile: PetProfile,
    library: PetLibrary,
    onChanged: () -> Unit,
) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember(profile.id) { mutableStateOf(profile.displayName) }

    Panel {
        Text("Pengaturan karakter", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            TextButton(onClick = { showRename = true }) { Text("Ubah nama") }
            TextButton(onClick = {
                library.update(profile.copy(animationOverrides = emptyMap(), animationSpeed = 1f))
                draft = profile.copy(animationOverrides = emptyMap(), animationSpeed = 1f)
                onChanged()
            }) { Text("Reset animasi") }
        }

        Text("Pemetaan animasi", style = MaterialTheme.typography.labelLarge)
        Text(
            "Otomatis memilih clip paling cocok dari nama file. Ubah manual bila modelmu memakai nama lain.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PetState.editable.forEach { state ->
            MappingRow(
                state = state,
                clips = profile.clipNames,
                override = draft.animationOverrides[state.name],
                onPick = { clip ->
                    draft = draft.withOverride(state, clip)
                    library.update(draft)
                    onChanged()
                },
            )
        }

        Text(
            "Kecepatan animasi: %.2f×".format(draft.animationSpeed),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        androidx.compose.material3.Slider(
            value = draft.animationSpeed,
            onValueChange = { draft = draft.copy(animationSpeed = it) },
            onValueChangeFinished = { library.update(draft); onChanged() },
            valueRange = 0.25f..3f,
        )

        Text("Kepribadian khusus karakter ini", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = draft.personality.systemInstruction,
            onValueChange = { draft = draft.copy(personality = draft.personality.copy(systemInstruction = it)) },
            label = { Text("System instruction") },
            supportingText = { Text("Kosong = pakai persona global dari tab Persona") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        OutlinedTextField(
            value = draft.personality.preferredLanguage,
            onValueChange = { draft = draft.copy(personality = draft.personality.copy(preferredLanguage = it)) },
            label = { Text("Bahasa balasan") },
            supportingText = { Text("Contoh: Indonesian, English") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.personality.idleMessages.joinToString("\n"),
            onValueChange = {
                draft = draft.copy(
                    personality = draft.personality.copy(
                        idleMessages = it.split('\n').map { line -> line.trim() }.filter { line -> line.isNotEmpty() },
                    ),
                )
            },
            label = { Text("Pesan saat ngambek (satu per baris)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedTextField(
            value = draft.personality.greetings.joinToString("\n"),
            onValueChange = {
                draft = draft.copy(
                    personality = draft.personality.copy(
                        greetings = it.split('\n').map { line -> line.trim() }.filter { line -> line.isNotEmpty() },
                    ),
                )
            },
            label = { Text("Sapaan (satu per baris)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(
            onClick = { library.update(draft); onChanged() },
            modifier = Modifier.padding(top = 10.dp),
        ) { Text("Simpan karakter") }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Ubah nama") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Nama karakter") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (library.rename(profile.id, renameText)) {
                        renameText = renameText.trim()
                        draft = draft.copy(displayName = renameText.trim())
                        onChanged()
                    }
                    showRename = false
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun MappingRow(
    state: PetState,
    clips: List<String>,
    override: String?,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(state.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) {
                Text(
                    override ?: "Otomatis",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Otomatis (deteksi dari nama)") },
                    onClick = { open = false; onPick(null) },
                )
                clips.forEach { clip ->
                    DropdownMenuItem(
                        text = { Text(clip, fontFamily = FontFamily.Monospace) },
                        onClick = { open = false; onPick(clip) },
                    )
                }
            }
        }
    }
}

