package com.arisu.companion.settings

import android.content.Context
import android.content.SharedPreferences
import com.arisu.companion.ai.ProviderProfile
import com.arisu.companion.behavior.IdleTimer
import com.arisu.companion.behavior.QuietHours
import com.arisu.companion.security.CryptoStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * All mutable app settings, persisted locally in SharedPreferences.
 * Reactive surfaces use StateFlow; parsers persist on every write.
 * API keys are stored encrypted via [CryptoStore] (Android Keystore).
 */
class AppStateStore(context: Context, private val crypto: CryptoStore) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("arisu_settings", Context.MODE_PRIVATE)

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Provider profiles -------------------------------------------------

    @Serializable
    data class ProviderProfilesValue(val profiles: List<ProviderProfile>, val activeId: String)

    private val profilesFlow = MutableStateFlow(loadProfiles())

    val providerProfiles: StateFlow<ProviderProfilesValue> get() = profilesFlow

    fun saveProfiles(profiles: List<ProviderProfile>, activeId: String) {
        // Profile keys are already stored encrypted (see encryptKey); never pass plaintext here.
        val value = ProviderProfilesValue(profiles, activeId)
        prefs.edit()
            .putString(KEY_PROFILES, json.encodeToString(value))
            .putString(KEY_ACTIVE_PROFILE, activeId)
            .apply()
        profilesFlow.value = value
    }

    private fun loadProfiles(): ProviderProfilesValue {
        val defaults = defaultProfileValue()
        val raw = prefs.getString(KEY_PROFILES, null) ?: return defaults
        val stored = try {
            json.decodeFromString<ProviderProfilesValue>(raw)
        } catch (_: Exception) {
            return defaults
        }
        // Google AI Studio is the only provider now. Drop profiles saved by
        // older builds that pointed at other endpoints, and keep the selection
        // on whatever is actually left, so an upgrade never leaves the chat
        // pointing at a provider the UI can no longer show.
        val kept = stored.profiles.filter { it.isGemini }
        if (kept.isEmpty()) return defaults
        val activeId = kept.firstOrNull { it.id == stored.activeId }?.id ?: kept.first().id
        if (kept.size != stored.profiles.size || activeId != stored.activeId) {
            // Write straight to prefs: this runs while profilesFlow is still
            // being constructed, so it must not touch the flow.
            prefs.edit()
                .putString(KEY_PROFILES, json.encodeToString(ProviderProfilesValue(kept, activeId)))
                .putString(KEY_ACTIVE_PROFILE, activeId)
                .apply()
        }
        return ProviderProfilesValue(kept, activeId)
    }

    private fun defaultProfileValue(): ProviderProfilesValue =
        ProviderProfilesValue(listOf(ProviderProfile.default()), ProviderProfile.default().id)

    fun activeProfile(): ProviderProfile? {
        val value = profilesFlow.value
        return value.profiles.firstOrNull { it.id == value.activeId }
    }

    /** Encrypts an API key for storage in a [ProviderProfile]. Returns null on keystore failure. */
    fun encryptKey(plain: String): String? = crypto.encryptOrNull(plain)

    /** Decrypts a stored API key. Returns null when absent or corrupt. */
    fun decryptKey(cipher: String): String? = crypto.decryptOrNull(cipher)
    private fun saveProfilesRaw(profiles: List<ProviderProfile>, activeId: String) {
        val value = ProviderProfilesValue(profiles, activeId)
        prefs.edit()
            .putString(KEY_PROFILES, json.encodeToString(value))
            .putString(KEY_ACTIVE_PROFILE, activeId)
            .apply()
        profilesFlow.value = value
    }

    // ---- Pet geometry ------------------------------------------------------

    private val petXFlow = MutableStateFlow(prefs.getFloat(KEY_PET_X, -1f))
    private val petYFlow = MutableStateFlow(prefs.getFloat(KEY_PET_Y, -1f))
    private val petScaleFlow = MutableStateFlow(prefs.getFloat(KEY_PET_SCALE, 1.0f))

    val petX: StateFlow<Float> get() = petXFlow
    val petY: StateFlow<Float> get() = petYFlow
    /**
     * Character size multiplier. 1.0 fits the model nicely in the pet window.
     */
    val petScale: StateFlow<Float> get() = petScaleFlow

    fun savePetPosition(x: Float, y: Float) {
        prefs.edit().putFloat(KEY_PET_X, x).putFloat(KEY_PET_Y, y).apply()
        petXFlow.value = x
        petYFlow.value = y
    }

    fun resetPetPosition() {
        prefs.edit().putFloat(KEY_PET_X, -1f).putFloat(KEY_PET_Y, -1f).apply()
        petXFlow.value = -1f
        petYFlow.value = -1f
    }

    fun savePetScale(scale: Float) {
        val clamped = scale.coerceIn(0.4f, 2.2f)
        prefs.edit().putFloat(KEY_PET_SCALE, clamped).apply()
        petScaleFlow.value = clamped
    }

    // ---- Pet interaction / performance ------------------------------------

    private val fpsFlow = MutableStateFlow(prefs.getInt(KEY_FPS, 60))
    private val snapFlow = MutableStateFlow(prefs.getBoolean(KEY_SNAP, true))
    private val tapOpensChatFlow = MutableStateFlow(prefs.getBoolean(KEY_TAP_CHAT, true))
    val fpsLimit: StateFlow<Int> get() = fpsFlow
    val snapToEdge: StateFlow<Boolean> get() = snapFlow
    val tapOpensChat: StateFlow<Boolean> get() = tapOpensChatFlow

    fun saveFpsLimit(fps: Int) {
        val v = fps.coerceIn(15, 60)
        prefs.edit().putInt(KEY_FPS, fps).apply()
        fpsFlow.value = v
    }

    fun saveSnapToEdge(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SNAP, enabled).apply()
        snapFlow.value = enabled
    }

    fun saveTapOpensChat(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TAP_CHAT, enabled).apply()
        tapOpensChatFlow.value = enabled
    }

    // ---- Idle behaviour (sulking) -----------------------------------------

    private val sulkFlow = MutableStateFlow(prefs.getBoolean(KEY_SULK, true))
    private val idleTimeoutFlow = MutableStateFlow(
        prefs.getInt(KEY_IDLE_TIMEOUT, (IdleTimer.DEFAULT_TIMEOUT_MS / 1000).toInt()),
    )
    private val idleBubbleFlow = MutableStateFlow(prefs.getBoolean(KEY_IDLE_BUBBLE, true))
    private val animSpeedFlow = MutableStateFlow(prefs.getFloat(KEY_ANIM_SPEED, 1f))
    private val reducedMotionFlow = MutableStateFlow(prefs.getBoolean(KEY_REDUCED_MOTION, false))
    private val quietEnabledFlow = MutableStateFlow(prefs.getBoolean(KEY_QUIET_ON, false))
    private val quietStartFlow = MutableStateFlow(prefs.getInt(KEY_QUIET_START, 23))
    private val quietEndFlow = MutableStateFlow(prefs.getInt(KEY_QUIET_END, 7))
    private val debugOverlayFlow = MutableStateFlow(prefs.getBoolean(KEY_DEBUG_OVERLAY, false))

    /** Playful sulk after [idleTimeoutSeconds] without interaction. */
    val sulkingEnabled: StateFlow<Boolean> get() = sulkFlow
    val idleTimeoutSeconds: StateFlow<Int> get() = idleTimeoutFlow
    val idleSpeechBubble: StateFlow<Boolean> get() = idleBubbleFlow
    val animationSpeed: StateFlow<Float> get() = animSpeedFlow
    val reducedMotion: StateFlow<Boolean> get() = reducedMotionFlow
    val quietHoursEnabled: StateFlow<Boolean> get() = quietEnabledFlow
    val quietStartHour: StateFlow<Int> get() = quietStartFlow
    val quietEndHour: StateFlow<Int> get() = quietEndFlow
    val debugOverlay: StateFlow<Boolean> get() = debugOverlayFlow

    fun saveSulkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SULK, enabled).apply()
        sulkFlow.value = enabled
    }

    fun saveIdleTimeoutSeconds(seconds: Int) {
        val v = seconds.coerceIn((IdleTimer.MIN_TIMEOUT_MS / 1000).toInt(), (IdleTimer.MAX_TIMEOUT_MS / 1000).toInt())
        prefs.edit().putInt(KEY_IDLE_TIMEOUT, v).apply()
        idleTimeoutFlow.value = v
    }

    fun saveIdleSpeechBubble(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IDLE_BUBBLE, enabled).apply()
        idleBubbleFlow.value = enabled
    }

    fun saveAnimationSpeed(speed: Float) {
        val v = speed.coerceIn(0.25f, 3f)
        prefs.edit().putFloat(KEY_ANIM_SPEED, v).apply()
        animSpeedFlow.value = v
    }

    fun saveReducedMotion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCED_MOTION, enabled).apply()
        reducedMotionFlow.value = enabled
    }

    fun saveQuietHoursEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUIET_ON, enabled).apply()
        quietEnabledFlow.value = enabled
    }

    fun saveQuietWindow(startHour: Int, endHour: Int) {
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        prefs.edit().putInt(KEY_QUIET_START, start).putInt(KEY_QUIET_END, end).apply()
        quietStartFlow.value = start
        quietEndFlow.value = end
    }

    fun saveDebugOverlay(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_OVERLAY, enabled).apply()
        debugOverlayFlow.value = enabled
    }

    /** True when the sulk bubble must stay quiet right now. */
    fun isQuietNow(hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Boolean =
        quietEnabledFlow.value &&
            QuietHours.isQuiet(hourOfDay, quietStartFlow.value, quietEndFlow.value)

    private val autoStartFlow = MutableStateFlow(prefs.getBoolean(KEY_AUTO_START, false))
    val autoStartOnBoot: StateFlow<Boolean> get() = autoStartFlow

    fun saveAutoStartOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        autoStartFlow.value = enabled
    }

    // ---- Personality -------------------------------------------------------

    private val systemPromptFlow = MutableStateFlow(
        prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT,
    )
    private val nicknameFlow = MutableStateFlow(prefs.getString(KEY_NICKNAME, "") ?: "")
    private val languageFlow = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "") ?: "")
    private val memoryEnabledFlow = MutableStateFlow(prefs.getBoolean(KEY_MEMORY_ON, true))

    val systemPrompt: StateFlow<String> get() = systemPromptFlow
    val nickname: StateFlow<String> get() = nicknameFlow
    val preferredLanguage: StateFlow<String> get() = languageFlow
    val memoryEnabled: StateFlow<Boolean> get() = memoryEnabledFlow

    fun saveSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
        systemPromptFlow.value = prompt
    }

    fun resetPersonality() {
        saveSystemPrompt(DEFAULT_PROMPT)
        saveNickname("")
        saveLanguage("")
    }

    fun saveNickname(name: String) {
        prefs.edit().putString(KEY_NICKNAME, name).apply()
        nicknameFlow.value = name
    }

    fun saveLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
        languageFlow.value = language
    }

    fun saveMemoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEMORY_ON, enabled).apply()
        memoryEnabledFlow.value = enabled
    }

    // ---- AI rendering parameters ------------------------------------------

    private val temperatureFlow = MutableStateFlow(
        prefs.getFloat(KEY_TEMPERATURE, 0.8f),
    )
    private val maxTokensFlow = MutableStateFlow(prefs.getInt(KEY_MAX_TOKENS, 512))

    val temperature: StateFlow<Float> get() = temperatureFlow
    val maxTokens: StateFlow<Int> get() = maxTokensFlow

    fun saveTemperature(t: Float) {
        val v = t.coerceIn(0f, 2f)
        prefs.edit().putFloat(KEY_TEMPERATURE, v).apply()
        temperatureFlow.value = v
    }

    fun saveMaxTokens(n: Int) {
        val v = n.coerceIn(1, 8192)
        prefs.edit().putInt(KEY_MAX_TOKENS, v).apply()
        maxTokensFlow.value = v
    }

    // ---- Voice -------------------------------------------------------------

    private val voiceEnabledFlow = MutableStateFlow(prefs.getBoolean(KEY_VOICE_ON, false))
    private val voiceSpeedFlow = MutableStateFlow(prefs.getFloat(KEY_VOICE_SPEED, 1.0f))
    private val voiceNameFlow = MutableStateFlow(prefs.getString(KEY_VOICE_NAME, "") ?: "")

    val voiceEnabled: StateFlow<Boolean> get() = voiceEnabledFlow
    val ttsSpeed: StateFlow<Float> get() = voiceSpeedFlow
    val ttsVoiceName: StateFlow<String> get() = voiceNameFlow

    fun saveVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_ON, enabled).apply()
        voiceEnabledFlow.value = enabled
    }

    fun saveTtsSpeed(speed: Float) {
        val v = speed.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat(KEY_VOICE_SPEED, v).apply()
        voiceSpeedFlow.value = v
    }

    fun saveTtsVoice(name: String) {
        prefs.edit().putString(KEY_VOICE_NAME, name).apply()
        voiceNameFlow.value = name
    }

    companion object {
        private const val KEY_PROFILES = "provider_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
        private const val KEY_PET_X = "pet_x"
        private const val KEY_PET_Y = "pet_y"
        private const val KEY_PET_SCALE = "pet_scale"
        private const val KEY_FPS = "fps_limit"
        private const val KEY_SNAP = "snap_to_edge"
        private const val KEY_TAP_CHAT = "tap_opens_chat"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_SULK = "sulking_enabled"
        private const val KEY_IDLE_TIMEOUT = "idle_timeout_seconds"
        private const val KEY_IDLE_BUBBLE = "idle_speech_bubble"
        private const val KEY_ANIM_SPEED = "animation_speed"
        private const val KEY_REDUCED_MOTION = "reduced_motion"
        private const val KEY_QUIET_ON = "quiet_hours_enabled"
        private const val KEY_QUIET_START = "quiet_start_hour"
        private const val KEY_QUIET_END = "quiet_end_hour"
        private const val KEY_DEBUG_OVERLAY = "animation_debug_overlay"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LANGUAGE = "preferred_language"
        private const val KEY_MEMORY_ON = "memory_enabled"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_VOICE_ON = "voice_enabled"
        private const val KEY_VOICE_SPEED = "tts_speed"
        private const val KEY_VOICE_NAME = "tts_voice_name"

        const val DEFAULT_PROMPT =
            "You are Arisu, a friendly, curious, slightly playful AI companion who enjoys games, " +
                "technology, and chatting with your user. Keep replies conversational, warm and " +
                "concise — like a close friend, not a customer-service bot. Never claim to have " +
                "abilities you do not have. If you want to signal a mood you may finish your " +
                "message with a hidden tag like [[emotion:happy]], [[emotion:think]], " +
                "[[emotion:reply]] or [[emotion:sorry]]; use tags sparingly and only when they " +
                "genuinely fit."
    }
}
