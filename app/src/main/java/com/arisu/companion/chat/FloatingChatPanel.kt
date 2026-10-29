package com.arisu.companion.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arisu.companion.ai.ChatEngine
import com.arisu.companion.ai.ChatUiMessage
import com.arisu.companion.ui.BubbleShape
import com.arisu.companion.voice.VoiceController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The floating chat card shown above other apps. Independent from the
 * fullscreen settings app: closing it never hides the 3D pet.
 */
@Composable
fun FloatingChatPanel(
    engine: ChatEngine,
    voice: VoiceController,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
    onComposerFocus: () -> Unit,
    onComposerUnfocus: () -> Unit,
    onRequestMicPermission: () -> Unit = {},
) {
    val messages by engine.messages.collectAsState()
    val busy by engine.busy.collectAsState()
    val lastError by engine.lastError.collectAsState()
    val memoryProposal by engine.memoryProposal.collectAsState()
    val listening by voice.listening.collectAsState()
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp)),
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Arisu 🎀", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.OpenInFull, "expand", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { confirmClear = true }, modifier = Modifier.size(30.dp)) {
                    Text("🗑", fontSize = 13.sp)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.ArrowBack, "close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (memoryProposal != null) {
                MemoryProposalBanner(engine, memoryProposal!!)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty() && lastError == null) {
                    item {
                        Text(
                            "Say something and Arisu answers!\n" +
                                "Pet-only mode works without any API key — configure one in the app when ready.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 20.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(messages.size, key = { messages[it].localId }) { index ->
                    MessageBubble(
                        message = messages[index],
                        timeFmt = timeFmt,
                        onCopy = { clipboard?.setText(AnnotatedString(messages[index].content)) },
                        onRetry = { engine.retry() },
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Message Arisu…", fontSize = 13.sp, maxLines = 1) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            if (state.isFocused) onComposerFocus() else onComposerUnfocus()
                        },
                    shape = RoundedCornerShape(22.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = {
                    if (listening) voice.stopListening()
                    else {
                        if (voice.permissionGranted()) {
                            voice.startListening { text -> if (text.isNotBlank()) input = text }
                        } else {
                            onRequestMicPermission()
                        }
                    }
                }) {
                    Icon(
                        Icons.Filled.Mic,
                        "talk",
                        tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    )
                }
                if (busy) {
                    IconButton(onClick = { engine.stop() }) {
                        Icon(Icons.Filled.StopCircle, "stop", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = input
                            input = ""
                            if (!engine.send(text)) input = text
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Send, "send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            lastError?.takeIf { it.isNotEmpty() && !busy }?.let { err ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(err.take(160), color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (messages.lastOrNull()?.failed == true) {
                        TextButton(onClick = { engine.retry() }) {
                            Icon(Icons.Filled.Replay, null, Modifier.size(14.dp))
                            Text("Retry", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (confirmClear) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { confirmClear = false }) { Text("Keep", fontSize = 12.sp) }
                    TextButton(onClick = {
                        confirmClear = false
                        engine.clearConversation()
                    }) {
                        Text("Sure — clear history?", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryProposalBanner(engine: ChatEngine, proposal: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
        shape = BubbleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "Remember this?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(proposal, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { engine.approveMemory() }) { Text("Save", fontSize = 12.sp) }
                TextButton(onClick = { engine.dismissMemory() }) { Text("Discard", fontSize = 12.sp) }
            }
        }
    }
}

/** Inline images returned by a Gemini image model (e.g. gemini-3.1-flash-image). */
@Composable
private fun MessageImages(paths: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        paths.forEach { path ->
            val bitmap = remember(path) { android.graphics.BitmapFactory.decodeFile(path) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Gambar dari AI",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Text(
                    "🖼️ gambar tidak bisa dibuka",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    timeFmt: SimpleDateFormat,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
) {
    val isUser = message.role == "user"
    val bg = when {
        isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        message.failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    }
    val shape = if (isUser) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bg,
            shape = shape,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.images.isNotEmpty()) {
                    MessageImages(message.images)
                }
                Text(
                    when {
                        message.content.isNotEmpty() -> message.content
                        message.images.isNotEmpty() -> ""
                        message.streaming -> "…"
                        else -> "(empty)"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeFmt.format(Date(message.timestamp)),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (message.failed) {
                        Text(
                            "  retry?",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .clickable(onClick = onRetry),
                        )
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(16.dp)) {
                        Icon(Icons.Filled.CopyAll, "copy", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}
