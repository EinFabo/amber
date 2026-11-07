package app.amethyst.core.settings

import app.amethyst.core.engine.KeyBinding
import app.amethyst.core.engine.ServerNames
import app.amethyst.core.audio.VadConfig
import app.amethyst.core.bubble.BubbleSide
import app.amethyst.core.engine.AmberCatalog
import app.amethyst.core.engine.EngineKind
import app.amethyst.core.engine.EnginePreset
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.OpenAiCompatibleEngine
import app.amethyst.core.text.NormalizeOptions
import app.amethyst.core.text.SpliceOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EngineConfig(
    val id: String,
    val kind: EngineKind,
    val displayName: String,
    val baseUrl: String? = null,
    val modelId: String? = null,
    /** BCP-47, or null for automatic detection. */
    val languageTag: String? = null,
    /** Alias under which the key lives in EncryptedSharedPreferences - never the key itself. */
    val apiKeyAlias: String? = null,
    val enabled: Boolean = true,
    /**
     * Until schema 7 the cleanup model lived here, tied to the engine. It is only read to move
     * it to [AmethystSettings.cleanup]; nothing else uses it any more.
     */
    val cleanupModel: String? = null,
    /**
     * Send only over a verified enclave connection, or not at all. Set by presets like
     * Tinfoil: whether a connection gets verified is decided by its host, so without this an
     * edited URL would quietly turn the engine into a plain cloud one - with the same key.
     */
    val requireEnclave: Boolean = false,
    /**
     * The host the API key was given for ([app.amethyst.core.engine.KeyBinding]): the key goes
     * nowhere else, so an address changed afterwards - by mistake or by someone else - needs the
     * key typed again. Null until a key is bound; settings from before schema 12 bind to the
     * address they had.
     */
    val keyHost: String? = null,
) {
    companion object {
        fun fromPreset(preset: EnginePreset, languageTag: String? = null) = EngineConfig(
            id = preset.id,
            kind = preset.kind,
            displayName = preset.displayName,
            baseUrl = preset.baseUrl,
            modelId = preset.modelId,
            languageTag = languageTag,
            apiKeyAlias = if (preset.needsApiKey) "key.${preset.id}" else null,
            requireEnclave = preset.requireEnclave,
            keyHost = if (preset.needsApiKey) EnginePresets.hostOf(preset.baseUrl) else null,
        )

        /**
         * The language a new engine starts with, from the phone's languages ([phone], BCP-47,
         * most preferred first): the first one for engines that take one language, up to two
         * for Whisper to detect between - fixed to one, it translates everything else into it.
         * See [PhoneLanguages].
         */
        fun defaultLanguageTag(kind: EngineKind, phone: List<String>): String =
            PhoneLanguages.defaultLanguageTag(kind, phone)
    }
}

@Serializable
data class CaptureSettings(
    /**
     * End the recording after a pause. Off by default since roadmap P3: a dictation runs until
     * the second tap, so a pause to think never cuts it off. Settings are saved with every
     * field, so an install from before keeps what it had.
     */
    val autoStopOnSilence: Boolean = false,
    /**
     * Hard ceiling for one whole dictation, recording plus transcription. A stuck recognizer
     * or a server that accepts the upload and never answers would otherwise leave the bubble
     * spinning with no way back except force-stopping the app.
     */
    val totalTimeoutMs: Int = 180_000,
    val preRollMs: Int = 300,
    val silenceTimeoutMs: Int = 900,
    val minSpeechMs: Int = 350,
    val maxDurationMs: Int = 120_000,
    val stripFillers: Boolean = true,
    /** Adds the commas German requires before subordinate clauses; rules only, no model. */
    val germanCommas: Boolean = true,
    /** Recognize finished parts while still speaking, for engines that support it. */
    val chunking: Boolean = true,
    /** Shortest part worth cutting off; null keeps each engine's own (see EngineCapabilities). */
    val chunkMinMs: Int? = null,
    /**
     * When a server cannot be reached, recognize the recording with a local model that is
     * ready on the phone. Only ever towards the phone, so privacy never gets weaker.
     */
    @Deprecated("Since schema 7: AmethystSettings.fallback. Read only to migrate.")
    val localFallback: Boolean = true,
) {
    /**
     * [preRollMs] doubles as the calibration window: inside it the policy only learns the
     * room's noise floor and takes no stop decision, so a slow first syllable is never
     * mistaken for silence.
     */
    fun toVadConfig(frameMs: Int = 20): VadConfig = VadConfig(
        frameMs = frameMs,
        preRollMs = preRollMs,
        calibrationFrames = (preRollMs / frameMs).coerceIn(3, 50),
        minSpeechMs = minSpeechMs,
        // Auto-stop off: push the silence timeout out of reach so only a tap or the hard
        // duration cap ends the recording.
        silenceTimeoutMs = if (autoStopOnSilence) silenceTimeoutMs else Int.MAX_VALUE,
        maxDurationMs = maxDurationMs,
    )

    fun toNormalizeOptions(): NormalizeOptions = NormalizeOptions(stripFillers = stripFillers, germanCommas = germanCommas)

    /**
     * The watchdog always has to outlast the longest recording, the server's own time limit
     * for it, and then the phone recognizing it instead - or a dictation the local fallback
     * could still save is killed just before it gets the chance.
     */
    fun effectiveTotalTimeoutMs(): Long {
        val longest = maxDurationMs.toLong()
        return maxOf(totalTimeoutMs.toLong(), longest + OpenAiCompatibleEngine.requestTimeoutMs(longest) + AFTER_SERVER_MS)
    }

    private companion object {
        /** A cold local model loading and recognizing the recording, then the cleanup. */
        const val AFTER_SERVER_MS = 90_000L
    }
}

@Serializable
data class BubbleSettings(
    /**
     * Off hides the overlay without touching the accessibility service. Without this the only
     * way to get the bubble out of the way is the system accessibility screen, which also
     * kills insertion - so the in-app test path would die with it.
     */
    val enabled: Boolean = true,
    /** Until schema 11 the bubble could rest anywhere: 0f = left edge, 1f = right edge. */
    @Deprecated("Since schema 11: side. Read only to migrate.")
    val horizontalBias: Float? = null,
    /** Until schema 11: 0f = top of screen, 1f = bottom. */
    @Deprecated("Since schema 11: edgeY. Read only to migrate.")
    val verticalBias: Float? = null,
    /** The edge the bubble is docked on; a drag or a sideways fling changes it. */
    val side: BubbleSide = BubbleSide.RIGHT,
    /**
     * Its height along the edge without a keyboard, as a share of the screen between the
     * status bar and the gesture bar (0 = top, 1 = bottom), so a rotation keeps it in place.
     */
    val edgeY: Float = DEFAULT_EDGE_Y,
    /**
     * Its height while a keyboard is up: the gap between the bubble and the keys. Its own,
     * so the bubble clears an app's input bar; dragging it while the keyboard is up sets it.
     */
    val aboveKeyboardDp: Int = DEFAULT_ABOVE_KEYBOARD_DP,
    val sizeDp: Int = 56,
    val haptics: Boolean = true,
    /**
     * Show the bubble only while a text field has focus (and while a dictation is running),
     * so it never covers anything when there is nothing to dictate into.
     */
    val onlyWithTextField: Boolean = true,
    /**
     * With voice commands on ([AmethystSettings.commandsEnabled]), the bubble shows also where
     * no text field is, so "open WhatsApp" works from anywhere. Off by default: a bubble that
     * never leaves surprised its user.
     */
    val everywhereForCommands: Boolean = false,
    /** How visible the bubble is at rest, 0.2..1; while dictating it is always fully there. */
    val restingOpacity: Float = 1f,
    /** Apps the bubble stays out of (games, apps with their own voice input). */
    val hiddenIn: List<String> = emptyList(),
) {
    companion object {
        /** About three quarters down, where a thumb rests. */
        const val DEFAULT_EDGE_Y = 0.72f

        /**
         * Clearly above a chat app's input bar (about 56-64 dp with its margins), so the bubble
         * never lands on the send or microphone button at the bar's end.
         */
        const val DEFAULT_ABOVE_KEYBOARD_DP = 80
    }
}

@Serializable
data class InsertionSettings(
    val autoSpace: Boolean = true,
    val autoCapitalize: Boolean = true,
    val gluePunctuation: Boolean = true,
    val rememberStrategyPerApp: Boolean = true,
    /**
     * Off by default: text goes straight into the field, and a dictation that cannot be
     * inserted is reported rather than parked on the clipboard. On allows pasting and the
     * copy-as-last-resort, at the price of every such dictation landing in clipboard history.
     */
    val allowClipboard: Boolean = false,
) {
    fun toSpliceOptions(): SpliceOptions = SpliceOptions(
        autoSpace = autoSpace,
        autoCapitalize = autoCapitalize,
        gluePunctuation = gluePunctuation,
    )
}

@Serializable
data class AmethystSettings(
    val activeEngineId: String = EnginePresets.ANDROID_ON_DEVICE.id,
    val engines: List<EngineConfig> = emptyList(),
    val capture: CaptureSettings = CaptureSettings(),
    val bubble: BubbleSettings = BubbleSettings(),
    val insertion: InsertionSettings = InsertionSettings(),
    /** Per-app writing style and cleanup, edited under Settings -> App styles. */
    val profiles: List<AppProfile> = AppProfiles.DEFAULTS,
    val stats: StatsSettings = StatsSettings(),
    /**
     * Who rewrites the recognized text - chosen apart from who recognizes it, so local
     * recognition can be cleaned up by a server model (or, later, a local one). Null: nobody.
     */
    val cleanup: CleanupChoice? = null,
    /**
     * The user's own addition to the cleanup for every app ("write numbers as digits"), under
     * the fixed rules and before an app's own instruction; see
     * [app.amethyst.core.text.ChatCleanup.systemPrompt]. Absent in older settings: none.
     */
    val cleanupInstruction: String? = null,
    /** Who takes a recording the engine's server could not; see [app.amethyst.core.engine.Fallback]. */
    val fallback: FallbackSettings = FallbackSettings(),
    /** Whether the introduction ran; a fresh install starts without, see [SettingsCodec.migrate]. */
    val onboardingDone: Boolean = false,
    val commands: CommandSettings = CommandSettings(),
    /**
     * The model lists Amber PCs and Amber Servers sent, by engine id - kept so the pickers work
     * offline and on a cold start. See [app.amethyst.core.engine.AmberCatalogs].
     */
    val serverCatalogs: Map<String, StoredCatalog> = emptyMap(),
    /**
     * The update check of builds from GitHub releases, and what it found; see
     * [app.amethyst.core.update.UpdatePolicy]. Absent in older settings, which check by default.
     */
    val updates: UpdateSettings = UpdateSettings(),
    /** How the app itself looks: the simple or the advanced settings. */
    val ui: UiSettings = UiSettings(),
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val activeEngine: EngineConfig?
        get() = engines.firstOrNull { it.id == activeEngineId }

    /**
     * Whether voice commands work - the one place to ask, never [commands] directly: off while
     * the feature is out of the build ([Features.VOICE_COMMANDS]), whatever was stored.
     */
    val commandsEnabled: Boolean
        get() = commands.effective()

    /**
     * The list [engine]'s Amber server sent last - only while the engine still points at the
     * address it came from, however that is spelled.
     */
    fun catalogFor(engine: EngineConfig): AmberCatalog? =
        serverCatalogs[engine.id]?.takeIf { it.isFor(engine.baseUrl) }?.toCatalog()

    /**
     * [config] added or in place of its old self. A model list its server sent for another
     * address goes: it would only ever be stale.
     */
    fun withEngine(config: EngineConfig): AmethystSettings {
        val replaced = engines.map { if (it.id == config.id) config else it }
        val stale = serverCatalogs[config.id]?.takeUnless { it.isFor(config.baseUrl) } != null
        return copy(
            engines = if (replaced.any { it.id == config.id }) replaced else engines + config,
            serverCatalogs = if (stale) serverCatalogs - config.id else serverCatalogs,
        )
    }

    companion object {
        /**
         * 2: Whisper takes a list of languages. 3: Tinfoil moved behind one router.
         * 4: per-app profiles - nothing to migrate, an absent field decodes to the defaults.
         * 5: the Tinfoil engine requires its enclave.
         * 6: ElevenLabs Realtime engines (never implemented) are dropped.
         * 7: cleanup and fallback chosen on their own, no longer per engine.
         * 8: the introduction - settings from before it count as introduced.
         * 9: the cleanup per app is a choice (default, off, a model) instead of a switch.
         * 10: the model lists of Amber servers - nothing to migrate, an absent field decodes
         * to none, and the next fetch fills it.
         * 11: the bubble docks on a side edge, with its own height above the keyboard - the
         * free position becomes a side and a height along it.
         * The update check ([updates]) needed no new version: an absent field decodes to its
         * defaults. Nor did the "Advanced settings" switch ([ui]): absent, it decodes to on
         * (settings from before it), and a new install starts with it off ([defaults]).
         * See [SettingsCodec.migrate].
         */
        const val SCHEMA_VERSION = 12

        /**
         * What the version 1 migration gives Whisper engines that version 1 had forced to
         * German. New engines take their languages from the phone instead ([PhoneLanguages]).
         */
        const val V1_WHISPER_LANGUAGES = "de,en"

        /**
         * First launch: only the zero-setup engine is configured, so dictation works before
         * the user has typed a single URL or downloaded a model - in the phone's first
         * language ([phone], BCP-47, most preferred first).
         */
        fun defaults(phone: List<String>): AmethystSettings = AmethystSettings(
            activeEngineId = EnginePresets.ANDROID_ON_DEVICE.id,
            engines = listOf(
                EngineConfig.fromPreset(
                    EnginePresets.ANDROID_ON_DEVICE,
                    EngineConfig.defaultLanguageTag(EngineKind.ANDROID_ON_DEVICE, phone),
                ),
            ),
            // A new install sees the few settings most people need; see [SettingsLayout].
            ui = UiSettings(advanced = false),
        )
    }
}

/** Serialises settings for DataStore. Tolerates unknown keys so older builds survive upgrades. */
object SettingsCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(settings: AmethystSettings): String = json.encodeToString(settings)

    /**
     * Falls back to defaults rather than throwing: corrupt settings must not brick the app.
     * Nothing saved yet (a first launch) decodes to the defaults too, in the [phone]'s
     * languages; anything saved keeps its own.
     */
    fun decode(raw: String, phone: List<String>): AmethystSettings = runCatching {
        migrate(json.decodeFromString<AmethystSettings>(raw))
    }.getOrElse { AmethystSettings.defaults(phone) }

    /**
     * Version 1 put "de-DE" on every engine it created, Whisper included. Forced to German,
     * Whisper translates English speech instead of writing it down, so that default - never
     * something the user chose - becomes the German/English pair Whisper detects between.
     */
    internal fun migrate(settings: AmethystSettings): AmethystSettings {
        if (settings.schemaVersion >= AmethystSettings.SCHEMA_VERSION) return settings
        // Version 6: ElevenLabs Realtime was offered but never worked.
        val kept = settings.engines.filterNot { it.kind == EngineKind.ELEVENLABS_REALTIME }
        val active = settings.activeEngineId.takeIf { id -> kept.any { it.id == id } }
            ?: kept.firstOrNull()?.id ?: EnginePresets.ANDROID_ON_DEVICE.id
        // Version 7: the cleanup the active engine had becomes the cleanup; the fallback switch
        // becomes the fallback setting.
        val cleanup = settings.cleanup ?: if (settings.schemaVersion < 7) {
            kept.firstOrNull { it.id == active && it.kind == EngineKind.OPENAI_COMPATIBLE }
                ?.let { engine -> engine.cleanupModel?.trim()?.takeIf { it.isNotEmpty() }?.let { CleanupChoice(engine.id, it) } }
        } else {
            null
        }
        val fallback = if (settings.schemaVersion < 7 && !settings.capture.localFallback) {
            FallbackSettings(FallbackMode.OFF)
        } else {
            settings.fallback
        }
        val migrated = settings.copy(
            activeEngineId = active,
            cleanup = cleanup,
            fallback = fallback,
            // Version 8: whoever had settings before the introduction existed has set Amber up.
            onboardingDone = settings.onboardingDone || settings.schemaVersion < 8,
            profiles = if (settings.schemaVersion < 9) settings.profiles.map(::migrateProfile) else settings.profiles,
            bubble = if (settings.schemaVersion < 11) migrateBubble(settings.bubble) else settings.bubble,
            engines = kept.map { engine ->
                when {
                    engine.kind == EngineKind.WHISPER_CPP && engine.languageTag == "de-DE" ->
                        engine.copy(languageTag = AmethystSettings.V1_WHISPER_LANGUAGES)
                    // Version 3: Tinfoil's per-model hosts (*.inf.tinfoil.sh) no longer resolve.
                    engine.baseUrl?.contains(".inf.tinfoil.sh") == true ->
                        engine.copy(baseUrl = EnginePresets.TINFOIL_BASE_URL)
                    else -> engine
                }.let { if (it.id == EnginePresets.TINFOIL.id) it.copy(requireEnclave = true) else it }
                    .copy(cleanupModel = null)
                    // Version 12: a stored key belongs to the address it was used with so far.
                    .let { if (settings.schemaVersion < 12) KeyBinding.bindIfUnbound(it) else it }
            },
            schemaVersion = AmethystSettings.SCHEMA_VERSION,
        )
        // Version 12: servers are named after what they are - "Amber PC", "Groq", a host.
        return if (settings.schemaVersion < 12) ServerNames.apply(migrated) else migrated
    }
}

/**
 * Version 11: the bubble docks on the side it was nearer to (the middle goes right, where it
 * starts), at the height it had. Settings without a position keep the defaults.
 */
@Suppress("DEPRECATION")
private fun migrateBubble(bubble: BubbleSettings): BubbleSettings = bubble.copy(
    side = bubble.horizontalBias?.let { if (it < 0.5f) BubbleSide.LEFT else BubbleSide.RIGHT } ?: bubble.side,
    edgeY = bubble.verticalBias?.coerceIn(0f, 1f) ?: bubble.edgeY,
    horizontalBias = null,
    verticalBias = null,
)

/** Version 9: the per-app switch "never" becomes Off, "follow" and "always" the default. */
@Suppress("DEPRECATION")
private fun migrateProfile(profile: AppProfile): AppProfile = profile.copy(
    appCleanup = if (profile.cleanup == false) AppCleanup(AppCleanupMode.OFF) else profile.appCleanup,
    cleanup = null,
)

/**
 * A cleanup model on one of the configured server engines, whose address, key and enclave
 * check it uses. Later also a model on the phone.
 */
@Serializable
data class CleanupChoice(val engineId: String, val model: String)

/**
 * An [AmberCatalog] as stored, with the [address] it was fetched from as a fingerprint
 * ([AmberCatalog.fingerprint]) - never the address itself, whose path holds the server's key.
 * An engine pointed elsewhere since has no list until its new address sends one; an entry
 * without a fingerprint fits no address.
 */
@Serializable
data class StoredCatalog(
    val address: String = "",
    val service: String,
    val version: String = "",
    val speech: List<StoredModel> = emptyList(),
    val cleanup: List<StoredModel> = emptyList(),
    /**
     * The upload formats the server takes; a list stored before they existed takes WAV. Opus
     * goes once the server refused it ([app.amethyst.core.engine.AmberCatalogs.compressionRefused]),
     * until the next fetch replaces the list.
     */
    val audio: List<String> = listOf(AmberCatalog.AUDIO_WAV),
) {
    fun toCatalog(): AmberCatalog = AmberCatalog(service, version, speech.map { it.toChoice() }, cleanup.map { it.toChoice() }, audio)

    /** Fetched from [baseUrl], however it is spelled now. */
    fun isFor(baseUrl: String?): Boolean =
        address.isNotEmpty() && !baseUrl.isNullOrBlank() && address == AmberCatalog.fingerprint(baseUrl)

    companion object {
        fun of(baseUrl: String, catalog: AmberCatalog) = StoredCatalog(
            address = AmberCatalog.fingerprint(baseUrl),
            service = catalog.service,
            version = catalog.version,
            speech = catalog.speech.map(StoredModel::of),
            cleanup = catalog.cleanup.map(StoredModel::of),
            audio = catalog.audio,
        )
    }
}

/** One model of a [StoredCatalog]: the id sent as `model`, and what the picker shows. */
@Serializable
data class StoredModel(val id: String, val label: String, val note: String = "", val recommended: Boolean = false) {
    fun toChoice() = EnginePresets.ModelChoice(id, label, note, recommended = recommended)

    companion object {
        fun of(choice: EnginePresets.ModelChoice) = StoredModel(choice.id, choice.label, choice.note, choice.recommended)
    }
}

/**
 * The update check: only builds signed with the GitHub release key ever run it
 * ([app.amethyst.core.update.ReleaseChannel]), at most once a day. Versions are kept as
 * "0.24.0", without the tag's "v".
 */
@Serializable
data class UpdateSettings(
    /** Ask GitHub for the latest release; the user can switch it off under Settings -> Updates. */
    val enabled: Boolean = true,
    /** When the last check ran, successful or not (wall clock, ms); 0 for never. */
    val lastCheckedAt: Long = 0,
    /** The last check got no usable answer; retried at the next due time. */
    val lastCheckFailed: Boolean = false,
    /** ... and no answer at all (offline, no name lookup, a timeout): retried within the hour. */
    val lastCheckOffline: Boolean = false,
    /** The latest release the last successful check found, and its page. */
    val latestVersion: String? = null,
    val latestUrl: String? = null,
    /** The version a notification was posted for, so each version notifies once. */
    val notifiedVersion: String? = null,
    /** The version whose card on Home the user dismissed. */
    val dismissedVersion: String? = null,
)

/**
 * Voice commands from the bubble ("open WhatsApp", "go back"). Off unless the user switches them
 * on and agrees to what they do: while off, Amber never acts for the user.
 */
@Serializable
data class CommandSettings(val enabled: Boolean = false) {
    /**
     * Switched on, and in the build ([featureOn]). Read through
     * [AmethystSettings.commandsEnabled]; [enabled] alone is only the stored switch.
     */
    fun effective(featureOn: Boolean = Features.VOICE_COMMANDS): Boolean = featureOn && enabled
}

/** How the app itself looks. */
@Serializable
data class UiSettings(
    /**
     * Show every setting, not only the simple ones ([SettingsLayout]). The default, on, is what
     * settings from before this switch decode to - their users keep seeing what they set up;
     * a new install starts with it off ([AmethystSettings.defaults]).
     */
    val advanced: Boolean = true,
)

@Serializable
enum class FallbackMode {
    /** The best local model that is ready: Parakeet, then Whisper. */
    AUTOMATIC,

    /** A failed server is a failed dictation. */
    OFF,

    /** The engine in [FallbackSettings.engineId], if it is allowed and ready. */
    ENGINE,
}

@Serializable
data class FallbackSettings(
    val mode: FallbackMode = FallbackMode.AUTOMATIC,
    val engineId: String? = null,
    /**
     * A local fallback recognizes the recording while a server engine records, so its text is
     * ready the moment the server fails or hangs ([app.amethyst.core.engine.ReadyFallback]).
     * Off by default: it costs the phone's processor and battery on every dictation.
     */
    val prepare: Boolean = false,
)

/** The statistics: kept on this phone only, without text or audio. */
@Serializable
data class StatsSettings(
    val keep: Boolean = true,
    /** What "time saved" compares with; 40 words a minute is a typical phone typist. */
    val typingWpm: Int = 40,
)
