package com.arisu.companion

import android.content.Context
import com.arisu.companion.ai.AiClient
import com.arisu.companion.ai.ChatEngine
import com.arisu.companion.ai.GeminiClient
import com.arisu.companion.database.AppDatabase
import com.arisu.companion.database.ChatDao
import com.arisu.companion.pet.PetLibrary
import com.arisu.companion.security.CryptoStore
import com.arisu.companion.security.KeystoreVault
import com.arisu.companion.settings.AppStateStore
import com.arisu.companion.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Hand-rolled dependency container (no Hilt to keep the build light).
 * All long-lived singletons for both the settings app and the floating-pet
 * service come from here, so chat state is shared between them.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val cryptoStore: CryptoStore by lazy { CryptoStore(KeystoreVault()) }
    val appState: AppStateStore by lazy { AppStateStore(appContext, cryptoStore) }
    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val chatDao: ChatDao get() = database.chatDao()

    /** Installed 3D characters (bundled Arisu + imports). */
    val petLibrary: PetLibrary by lazy { PetLibrary(appContext) }

    val aiClient: AiClient by lazy { AiClient() }
    val geminiClient: GeminiClient by lazy { GeminiClient() }

    private val imageDir: File get() = File(appContext.filesDir, "chat_images")

    val chatEngine: ChatEngine by lazy {
        ChatEngine(
            scope = applicationScope,
            dao = chatDao,
            memoryDao = database.memoryDao(),
            store = appState,
            client = aiClient,
            gemini = geminiClient,
            petLibrary = petLibrary,
            imageDir = imageDir,
        )
    }

    val voiceController: VoiceController by lazy { VoiceController(appContext, appState) }
}
