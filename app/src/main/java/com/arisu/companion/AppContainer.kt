package com.arisu.companion

import android.content.Context
import com.arisu.companion.ai.AiClient
import com.arisu.companion.ai.ChatEngine
import com.arisu.companion.database.AppDatabase
import com.arisu.companion.database.ChatDao
import com.arisu.companion.security.CryptoStore
import com.arisu.companion.security.KeystoreVault
import com.arisu.companion.settings.AppStateStore
import com.arisu.companion.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency container (no Hilt to keep the build light).
 * All long-lived singletons for both the settings app and the floating-pet
 * service come from here, so chat state is shared between them.
 */
class AppContainer(context: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val cryptoStore: CryptoStore by lazy { CryptoStore(KeystoreVault()) }
    val appState: AppStateStore by lazy { AppStateStore(context, cryptoStore) }
    val database: AppDatabase by lazy { AppDatabase.create(context) }
    val chatDao: ChatDao get() = database.chatDao()
    val aiClient: AiClient by lazy { AiClient() }
    val chatEngine: ChatEngine by lazy {
        ChatEngine(scope = applicationScope, dao = chatDao, memoryDao = database.memoryDao(), store = appState, client = aiClient)
    }
    val voiceController: VoiceController by lazy { VoiceController(context, appState) }
}
