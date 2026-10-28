package com.arisu.companion.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arisu.companion.MainActivity
import com.arisu.companion.ui.Panel
import com.arisu.companion.ui.ToggleRow
import kotlinx.coroutines.launch

@Composable
fun PersonalityScreen(activity: MainActivity) {
    val store = activity.container.appState
    val prompt by store.systemPrompt.collectAsState()
    val nickname by store.nickname.collectAsState()
    val language by store.preferredLanguage.collectAsState()
    val memoryOn by store.memoryEnabled.collectAsState()
    val voiceOn by store.voiceEnabled.collectAsState()
    val ttsSpeed by store.ttsSpeed.collectAsState()
    val memories by activity.container.database.memoryDao().all().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Personality", style = MaterialTheme.typography.headlineSmall)

        Panel {
            OutlinedTextField(
                value = prompt,
                onValueChange = { store.saveSystemPrompt(it) },
                label = { Text("System prompt (who Arisu is)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                minLines = 5,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { store.resetPersonality() }) { Text("Reset personality") }
                TextButton(onClick = { store.saveSystemPrompt(com.arisu.companion.settings.AppStateStore.DEFAULT_PROMPT) }) {
                    Text("Restore default text")
                }
            }
        }

        Panel {
            OutlinedTextField(
                value = nickname,
                onValueChange = { store.saveNickname(it) },
                label = { Text("Your nickname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(4.dp))
            OutlinedTextField(
                value = language,
                onValueChange = { store.saveLanguage(it) },
                label = { Text("Preferred reply language") },
                placeholder = { Text("e.g. English, 日本語… (empty = auto)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Panel {
            ToggleRow(
                label = "Remember facts about me",
                checked = memoryOn,
                onChange = { store.saveMemoryEnabled(it) },
                supporting = "Stored ONLY on this device and only when you approve each memory.",
            )
            Text(
                "Saved memories (${memories.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(memories, key = { it.id }) { memory ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            memory.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            activity.container.applicationScope.launch {
                                activity.container.database.memoryDao().delete(memory.id)
                            }
                        }) { Text("Delete") }
                    }
                }
            }
            if (memories.isEmpty()) {
                Text(
                    "Nothing stored yet. Arisu will propose memories when you say something like \"remember that …\".",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Panel {
            ToggleRow(
                label = "Voice mode (push-to-talk + TTS)",
                checked = voiceOn,
                onChange = { enabled ->
                    if (enabled) activity.requestMicPermission()
                    store.saveVoiceEnabled(enabled)
                },
                supporting = "Mic permission is requested only when you enable this. Android TTS voices are used (no licensed character voice).",
            )
            com.arisu.companion.ui.LabeledSlider(
                label = "Speaking speed",
                value = ttsSpeed,
                range = 0.5f..2f,
                format = { "%.1f×".format(it) },
            ) { store.saveTtsSpeed(it) }
        }
    }
}

