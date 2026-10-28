package com.arisu.companion.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arisu.companion.MainActivity
import com.arisu.companion.database.ChatMessageEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatHistoryScreen(activity: MainActivity) {
    val dao = activity.container.database.chatDao()
    val sessions by dao.sessions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ChatMessageEntity>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Chat history", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search messages") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.sessionId }) { session ->
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (session.sessionId) {
                                        "default" -> "★ Active conversation (${session.messageCount} msg)"
                                        else -> "Archived · ${session.messageCount} messages"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Last activity: " + fmt.format(Date(session.lastTimestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch { dao.deleteSession(session.sessionId) }
                            }) { Text("Delete") }
                        }
                    }
                }
            }

            if (query.isNotBlank() && searchResults.isNotEmpty()) {
                item { Text("Matches for \"$query\"", style = MaterialTheme.typography.titleSmall) }
                items(searchResults, key = { it.id }) { message ->
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Text(
                            when (message.role) {
                                "user" -> "You"
                                "assistant" -> "Arisu"
                                else -> message.role
                            } + " · " + fmt.format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(message.content.take(200), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.isBlank()) {
            searchResults = emptyList()
        } else {
            searchResults = dao.search("default", query).take(20) +
                sessions.filter { it.sessionId != "default" }.flatMap { session ->
                    dao.search(session.sessionId, query).take(5)
                }
        }
    }
}
