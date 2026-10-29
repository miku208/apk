package com.arisu.companion.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arisu.companion.MainActivity
import com.arisu.companion.ai.AiClient
import com.arisu.companion.ai.ProviderProfile
import com.arisu.companion.ui.LabeledSlider
import com.arisu.companion.ui.Panel
import kotlinx.coroutines.launch

/**
 * BYOK settings. API keys are encrypted via Android Keystore before they ever
 * touch persistent storage; they are shown masked and never logged.
 */
@Composable
fun AiSettingsScreen(activity: MainActivity) {
    val store = activity.container.appState
    val profilesValue by store.providerProfiles.collectAsState()

    var selectedId by remember(profilesValue.activeId) { mutableStateOf(profilesValue.activeId) }
    var drafts by remember(profilesValue.profiles) {
        mutableStateOf(
            profilesValue.profiles.associate { profile ->
                profile.id to DraftProfile(
                    name = profile.name,
                    baseUrl = profile.baseUrl,
                    modelId = profile.modelId,
                    keyPlain = store.decryptKey(profile.apiKeyCipher).orEmpty(),
                    streaming = profile.streaming,
                    temperature = profile.temperature ?: 0.8f,
                    maxTokens = profile.maxTokens ?: 512,
                    imageModelId = profile.imageModelId,
                )
            },
        )
    }
    var showKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Google AI Studio (BYOK)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Aplikasi memakai API resmi Google AI Studio (models/…:generateContent). Key kamu " +
                "disimpan terenkripsi di perangkat ini saja dan hanya dikirim ke " +
                "generativelanguage.googleapis.com — tidak ada provider lain yang dipakai.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- profile switcher chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profilesValue.profiles.forEach { profile ->
                FilterChip(
                    selected = profile.id == selectedId,
                    onClick = { selectedId = profile.id; store.saveProfiles(profilesValue.profiles, profile.id) },
                    label = { Text(profile.name) },
                )
            }
        }

        val editable = profilesValue.profiles.firstOrNull { it.id == selectedId }
        val draft = drafts[selectedId]
        if (editable != null && draft != null) {
            Panel {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> drafts = drafts + (editable.id to draft.copy(name = name)) },
                    label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(2.dp))
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { url -> drafts = drafts + (editable.id to draft.copy(baseUrl = url.trim())) },
                    label = { Text("Base URL") },
                    supportingText = { Text("API root, tanpa /models/…:generateContent") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.modelId,
                    onValueChange = { model -> drafts = drafts + (editable.id to draft.copy(modelId = model.trim())) },
                    label = { Text("Model ID (manual entry)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.keyPlain,
                    onValueChange = { key -> drafts = drafts + (editable.id to draft.copy(keyPlain = key)) },
                    label = { Text("API key") },
                    supportingText = { Text("Encrypted with Android Keystore (AES/GCM)") },
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                    },
                    visualTransformation = if (showKey) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editable.isGemini) {
                    Text(
                        "Model Gemini",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.arisu.companion.ai.GeminiProtocol.chatModels.take(3).forEach { model ->
                            FilterChip(
                                selected = draft.modelId == model,
                                onClick = { drafts = drafts + (editable.id to draft.copy(modelId = model)) },
                                label = {
                                    Text(
                                        model.removePrefix("gemini-"),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.imageModelId,
                        onValueChange = { model ->
                            drafts = drafts + (editable.id to draft.copy(imageModelId = model.trim()))
                        },
                        label = { Text("Model gambar (opsional)") },
                        supportingText = {
                            Text("Dipakai untuk perintah \"/img <prompt>\". Contoh: gemini-3.1-flash-image")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "⚠️ Pemakaian Gemini API ditagih ke akun Google kamu sendiri. Ada kuota gratis, " +
                            "tapi model tertentu butuh billing aktif. Gambar yang dikembalikan model gambar " +
                            "disimpan di penyimpanan privat aplikasi.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = {
                        runCatching {
                            activity.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(ProviderProfile.GEMINI_KEY_URL),
                                ),
                            )
                        }
                    }) { Text("Buat API key di AI Studio") }
                }

                LabeledSlider(
                    label = "Temperature",
                    value = draft.temperature,
                    range = 0f..2f,
                    format = { "%.2f".format(it) },
                ) { t -> drafts = drafts + (editable.id to draft.copy(temperature = t)) }
                LabeledSlider(
                    label = "Max output tokens",
                    value = draft.maxTokens.toFloat(),
                    range = 64f..4096f,
                    format = { "%.0f".format(it) },
                ) { t -> drafts = drafts + (editable.id to draft.copy(maxTokens = t.toInt())) }
                com.arisu.companion.ui.ToggleRow(
                    label = "Streaming responses",
                    checked = draft.streaming,
                    onChange = { on -> drafts = drafts + (editable.id to draft.copy(streaming = on)) },
                    supporting = "SSE chunks (recommended); turn off if a provider lacks stream support.",
                )

                // ---- actions
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val profile = ProfileSaver.applyDraft(store, profilesValue, editable, draft)
                        problems = profile.validate()
                        store.saveProfiles(
                            profilesValue.profiles.map { if (it.id == profile.id) profile else it },
                            selectedId,
                        )
                        testResult = "Saved ✔"
                    }) { Text("Save") }
                    Button(onClick = {
                        val profile = ProfileSaver.applyDraft(store, profilesValue, editable, draft)
                        scope.launch {
                            testResult = "Testing…"
                            val key = store.decryptKey(profile.apiKeyCipher)
                            val (ok, msg) = if (profile.isGemini) {
                                activity.container.geminiClient.testConnection(profile, key)
                            } else {
                                activity.container.aiClient.testConnection(profile, key)
                            }
                            testResult = if (ok) "✔ $msg" else "✘ $msg"
                        }
                    }) { Text("Test connection") }
                }
                testResult?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                }
                problems.forEach {
                    Text("⚠ ${'$'}it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val fresh = ProviderProfile.builtins()
                        .firstOrNull { it.id == editable.id }?.clone() ?: editable.clone()
                    store.saveProfiles(
                        profilesValue.profiles.map { if (it.id == editable.id) fresh else it },
                        editable.id,
                    )
                }) { Text("Reset profile") }
                if (profilesValue.profiles.size > 1) {
                    TextButton(onClick = {
                        val remaining = profilesValue.profiles.filter { it.id != editable.id }
                        val nextActive = remaining.firstOrNull()?.id ?: editable.id
                        store.saveProfiles(remaining, nextActive)
                        selectedId = nextActive
                    }) { Text("Delete profile") }
                }
            }
        }
    }
}

/** Local editing state for one provider profile. */
data class DraftProfile(
    val name: String,
    val baseUrl: String,
    val modelId: String,
    val keyPlain: String,
    val streaming: Boolean,
    val temperature: Float,
    val maxTokens: Int,
    val imageModelId: String = "",
)

object ProfileSaver {
    fun applyDraft(
        store: com.arisu.companion.settings.AppStateStore,
        profilesValue: com.arisu.companion.settings.AppStateStore.ProviderProfilesValue,
        editable: ProviderProfile,
        draft: DraftProfile,
    ): ProviderProfile {
        // Encrypt at save time only when the user typed a new key.
        val oldPlain = draft.keyPlain
        val cipher = if (oldPlain.isBlank()) {
            editable.apiKeyCipher
        } else {
            store.encryptKey(oldPlain) ?: editable.apiKeyCipher
        }
        return editable.copy(
            name = draft.name,
            baseUrl = draft.baseUrl.trim().removeSuffix("/"),
            modelId = draft.modelId.trim(),
            apiKeyCipher = cipher,
            streaming = draft.streaming,
            temperature = draft.temperature,
            maxTokens = draft.maxTokens,
            imageModelId = draft.imageModelId.trim(),
        )
    }
}
