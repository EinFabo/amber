package app.amethyst

import android.content.Context
import android.content.res.Configuration
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.bubble.QuickMenuItem
import app.amethyst.core.dictation.TextShape
import app.amethyst.core.engine.AmberMachine
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.onboarding.AmberVariant
import app.amethyst.core.onboarding.EngineName
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.InsertPart
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.Summary
import app.amethyst.core.settings.ThemeMode
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.update.Ago
import app.amethyst.core.update.LastCheck
import app.amethyst.core.update.UpdateSummary
import java.util.Locale

/**
 * Words for what :core says as values - a failure, a settings row's state, a name - in the
 * phone's language. :core decides which; the strings (values/, values-de/) say it. Outside
 * ui/ so the services and the bubble use it too (and tools/typecheck-android.sh checks it).
 */
object Texts {

    /**
     * [context] with English resources, for what goes into Diagnostics and the log: reports get
     * shared, and they stay in one language whatever the phone's is.
     */
    fun english(context: Context): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })

    fun problem(context: Context, problem: Problem): String = with(context) {
        when (problem) {
            Problem.NothingRecognized -> getString(R.string.problem_nothing_recognized)
            is Problem.NoApiKey -> getString(R.string.problem_no_api_key, catalog(this, problem.service))
            is Problem.AnswerUnreadable -> getString(R.string.problem_answer_unreadable, catalog(this, problem.service))
            is Problem.NoConnection -> getString(R.string.problem_no_connection, catalog(this, problem.service))
            is Problem.NoAnswerInTime -> getString(R.string.problem_no_answer_in_time, catalog(this, problem.service))
            Problem.KeyRejected -> getString(R.string.problem_key_rejected)
            Problem.AddressNotFound -> getString(R.string.problem_address_not_found)
            Problem.RecordingTooLong -> getString(R.string.problem_recording_too_long)
            Problem.AudioFormatRefused -> getString(R.string.problem_audio_format_refused)
            Problem.RateLimited -> getString(R.string.problem_rate_limited)
            is Problem.ServiceDown -> getString(R.string.problem_service_down, problem.code)
            is Problem.ServerError -> getString(R.string.problem_server_error, problem.code, catalog(this, problem.service))
            Problem.OutOfMemory -> getString(R.string.problem_out_of_memory)
            Problem.LocalModelFailed -> getString(R.string.problem_local_model_failed)
            Problem.ProcessorTooOld -> getString(R.string.problem_processor_too_old)
            Problem.WhisperModelMissing -> getString(R.string.problem_whisper_model_missing)
            Problem.ParakeetModelMissing -> getString(R.string.problem_parakeet_model_missing)
            Problem.NotSupported -> getString(R.string.problem_not_supported)
            is Problem.NotReady -> getString(
                R.string.problem_not_ready,
                catalog(this, problem.service),
                problem.issues.joinToString(" ") { issue(this, it) },
            )
            is Problem.RecordsByItself -> getString(R.string.problem_records_by_itself, catalog(this, problem.service))
            is Problem.NotToVerifiedEnclave -> getString(
                problem.payload.pick(R.string.problem_not_to_verified_enclave_recording, R.string.problem_not_to_verified_enclave_text),
            )
            is Problem.EnclaveKeyMismatch -> getString(
                problem.payload.pick(R.string.problem_enclave_key_mismatch_recording, R.string.problem_enclave_key_mismatch_text),
                catalog(this, problem.service),
            )
            is Problem.EnclaveNotVerified -> getString(
                problem.payload.pick(R.string.problem_enclave_not_verified_recording, R.string.problem_enclave_not_verified_text),
                catalog(this, problem.service),
            )
            Problem.EnclaveOtherAddress -> getString(R.string.problem_enclave_other_address)
            Problem.EnclaveCannotBind -> getString(R.string.problem_enclave_cannot_bind)
            Problem.UnencryptedRefused -> getString(R.string.problem_unencrypted_refused)
            is Problem.CleanupFailed -> getString(R.string.problem_cleanup_failed, problem.code)
            is Problem.CleanupDeclined -> getString(R.string.problem_cleanup_declined, catalog(this, problem.service))
            is Problem.NoCredit -> getString(R.string.problem_no_credit, catalog(this, problem.service))
            is Problem.CleanupModelUnknown -> getString(R.string.problem_cleanup_model_unknown, problem.model)
            Problem.CleanupUnreadable -> getString(R.string.problem_cleanup_unreadable)
            Problem.CleanupCutOff -> getString(R.string.problem_cleanup_cut_off)
            is Problem.CleanupChangedTooMuch -> getString(R.string.problem_cleanup_changed_too_much)
            Problem.NoSystemRecognizer -> getString(R.string.problem_no_system_recognizer)
            Problem.LanguageNotInstalled -> getString(R.string.problem_language_not_installed)
            Problem.MicrophoneUnreadable -> getString(R.string.problem_microphone_unreadable)
            Problem.MicrophonePermissionMissing -> getString(R.string.problem_microphone_permission_missing)
            Problem.RecognizerOffline -> getString(R.string.problem_recognizer_offline)
            Problem.NoSpeechHeard -> getString(R.string.problem_no_speech_heard)
            Problem.RecognizerBusy -> getString(R.string.problem_recognizer_busy)
            Problem.RecognizerCrashed -> getString(R.string.problem_recognizer_crashed)
            Problem.RecognitionFailed -> getString(R.string.problem_recognition_failed)
        }
    }

    /** What went wrong with [error]: its [Problem] when it is a dictation failure, else a plain "failed". */
    fun failure(context: Context, error: Throwable): String =
        (error as? SttFailure)?.let { problem(context, it.problem) } ?: context.getString(R.string.problem_recognition_failed)

    fun issue(context: Context, issue: ConfigIssue): String = with(context) {
        when (issue) {
            ConfigIssue.NoModelSelected -> getString(R.string.issue_no_model_selected)
            ConfigIssue.NotSupported -> getString(R.string.issue_not_supported)
            ConfigIssue.ModelNameMissing -> getString(R.string.issue_model_name_missing)
            is ConfigIssue.NoSpeechRecognition -> getString(R.string.issue_no_speech_recognition, issue.service)
            ConfigIssue.ApiKeyMissing -> getString(R.string.issue_api_key_missing)
            is ConfigIssue.EnclaveAddressOnly -> getString(R.string.issue_enclave_address_only, issue.address)
            is ConfigIssue.InvalidLanguageCode -> getString(R.string.issue_invalid_language_code, issue.tag)
            ConfigIssue.AddressMissing -> getString(R.string.issue_address_missing)
            is ConfigIssue.AddressScheme -> getString(
                R.string.issue_address_scheme,
                issue.schemes.reduce { a, b -> getString(R.string.issue_address_scheme_or, a, b) },
            )
            ConfigIssue.AddressNoHost -> getString(R.string.issue_address_no_host)
            ConfigIssue.AddressHasSpace -> getString(R.string.issue_address_has_space)
            ConfigIssue.HttpOnlyInOwnNetwork -> getString(R.string.issue_http_only_in_own_network)
            is ConfigIssue.KeyForOtherAddress -> getString(R.string.issue_key_for_other_address, issue.host)
        }
    }

    fun file(context: Context, problem: FileProblem): String = with(context) {
        when (problem) {
            is FileProblem.Incomplete -> getString(
                R.string.file_incomplete,
                android.text.format.Formatter.formatShortFileSize(this, problem.received),
                android.text.format.Formatter.formatShortFileSize(this, problem.expected),
            )
            FileProblem.ChecksumMismatch -> getString(R.string.file_checksum_mismatch)
            FileProblem.CouldNotSave -> getString(R.string.file_could_not_save)
            FileProblem.Interrupted -> getString(R.string.file_interrupted)
            FileProblem.NoModelInstalled -> getString(R.string.file_no_model_installed)
            is FileProblem.NotEnoughStorage -> problem.freeMb
                ?.let { getString(R.string.file_not_enough_storage_free, problem.neededMb, it) }
                ?: getString(R.string.file_not_enough_storage, problem.neededMb)
            is FileProblem.ServerAnswered -> getString(R.string.file_server_answered, problem.code)
            FileProblem.EmptyAnswer -> getString(R.string.file_empty_answer)
            FileProblem.NoConnection -> getString(R.string.file_no_connection)
            FileProblem.CannotOpen -> getString(R.string.file_cannot_open)
            FileProblem.TooSmallForWhisper -> getString(R.string.file_too_small_for_whisper)
            FileProblem.NotWhisperCpp -> getString(R.string.file_not_whisper_cpp)
            FileProblem.NotWhisper -> getString(R.string.file_not_whisper)
            FileProblem.Other -> getString(R.string.file_other)
        }
    }

    fun summary(context: Context, summary: Summary): String = with(context) {
        when (summary) {
            Summary.On -> getString(R.string.on)
            Summary.Off -> getString(R.string.off)
            Summary.NoneSelected -> getString(R.string.summary_none_selected)
            is Summary.Name -> summary.name
            is Summary.Recognition -> listOfNotNull(engineShortName(this, summary.engine), summary.place?.let { place(this, it) })
                .joinToString(" · ")
            Summary.OnButBubbleOff -> getString(R.string.summary_on_bubble_off)
            is Summary.Apps -> if (summary.count == 0) getString(R.string.summary_apps_none) else count(R.plurals.summary_apps, summary.count)
            Summary.NotNeeded -> getString(R.string.summary_not_needed)
            is Summary.Fallback -> {
                val who = summary.engine?.let { engineShortName(this, it) } ?: getString(R.string.summary_fallback_automatic)
                if (summary.prepared) getString(R.string.summary_prepared, who) else who
            }
            is Summary.StopsAfter -> getString(R.string.summary_stops_after, decimal(this, summary.seconds))
            Summary.RunsUntilTap -> getString(R.string.summary_runs_until_tap)
            is Summary.Inserting -> summary.parts.joinToString(", ") { insertPart(this, it) }
                .replaceFirstChar { it.titlecase(locale(this)) }
                .ifEmpty { getString(R.string.summary_as_recognized) }
            is Summary.Reliability -> if (summary.open == 0) getString(R.string.summary_all_set) else count(R.plurals.summary_things_open, summary.open)
            is Summary.Diagnostics -> if (summary.count == 0) getString(R.string.summary_nothing_dictated) else count(R.plurals.summary_last_dictations, summary.count)
            is Summary.MoreSettings -> count(R.plurals.summary_more_settings, summary.hidden)
            is Summary.ReliabilityOpen -> getString(R.string.summary_reliability_open, count(R.plurals.summary_things_open, summary.open))
            is Summary.Models -> count(R.plurals.summary_models, summary.count)
            is Summary.Theme -> theme(this, summary.mode)
        }
    }

    private fun insertPart(context: Context, part: InsertPart): String = context.getString(
        when (part) {
            InsertPart.SPACES -> R.string.insert_part_spaces
            InsertPart.CAPITALS -> R.string.insert_part_capitals
            InsertPart.PUNCTUATION -> R.string.insert_part_punctuation
            InsertPart.CLIPBOARD -> R.string.insert_part_clipboard
        },
    )

    private fun theme(context: Context, mode: ThemeMode): String = context.getString(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
        },
    )

    /** Where the voice goes, in a word or two. */
    fun place(context: Context, trust: TrustLevel): String = context.getString(
        when (trust) {
            TrustLevel.ON_DEVICE -> R.string.place_on_device
            TrustLevel.ENCLAVE_VERIFIED -> R.string.place_enclave
            TrustLevel.CLOUD -> R.string.place_cloud
        },
    )

    fun updates(context: Context, summary: UpdateSummary): String = with(context) {
        when (summary) {
            UpdateSummary.FromInstaller -> getString(R.string.updates_from_installer)
            UpdateSummary.Off -> getString(R.string.off)
            is UpdateSummary.Available -> getString(R.string.updates_available, summary.version)
            UpdateSummary.Daily -> getString(R.string.updates_daily)
        }
    }

    fun lastCheck(context: Context, check: LastCheck): String = with(context) {
        when (check) {
            LastCheck.NotYet -> getString(R.string.last_check_not_yet)
            is LastCheck.Failed -> {
                val what = getString(if (check.offline) R.string.last_check_offline else R.string.last_check_failed, ago(this, check.ago))
                when {
                    !check.retried -> what
                    check.offline -> getString(R.string.last_check_retry_hour, what)
                    else -> getString(R.string.last_check_retry_day, what)
                }
            }
            is LastCheck.Done -> when (check.found) {
                null -> ago(this, check.ago)
                "" -> getString(R.string.last_check_up_to_date, ago(this, check.ago))
                else -> getString(R.string.last_check_found, ago(this, check.ago), check.found)
            }
        }
    }

    fun ago(context: Context, ago: Ago): String = when (ago.scale) {
        Ago.Scale.JUST_NOW -> context.getString(R.string.ago_just_now)
        Ago.Scale.MINUTES -> context.count(R.plurals.ago_minutes, ago.count.toInt())
        Ago.Scale.HOURS -> context.count(R.plurals.ago_hours, ago.count.toInt())
        Ago.Scale.DAYS -> context.count(R.plurals.ago_days, ago.count.toInt())
    }

    fun engineName(context: Context, name: EngineName): String = when (name) {
        is EngineName.Plain -> name.name
        EngineName.AndroidRecognizer -> context.getString(R.string.engine_name_android)
        EngineName.ServerWithOwnKey -> context.getString(R.string.engine_name_server)
        is EngineName.Own -> engineName(context, name.engine)
    }

    /** A speech recognition's name: a preset's in the phone's language, a renamed or server one as it is. */
    fun engineName(context: Context, engine: EngineConfig): String = catalog(context, engine.displayName)

    /** [engineName] without its bracketed note: "Tinfoil (Enklave)" becomes "Tinfoil". */
    fun engineShortName(context: Context, engine: EngineConfig): String =
        SettingsSummaries.shortName(engineName(context, engine))

    fun quickAction(context: Context, action: QuickAction): String = context.getString(
        when (action) {
            QuickAction.INSERT_AGAIN -> R.string.quick_insert_again
            QuickAction.UNDO_LAST -> R.string.quick_undo_last
            QuickAction.COPY_LAST -> R.string.quick_copy_last
            QuickAction.CLEANUP_OFF_HERE -> R.string.quick_cleanup_off_here
            QuickAction.CLEANUP_ON_HERE -> R.string.quick_cleanup_on_here
            QuickAction.HIDE_HERE -> R.string.quick_hide_here
            QuickAction.CANCEL -> R.string.quick_cancel
            QuickAction.SEND_WITHOUT_CLEANUP -> R.string.quick_no_cleanup
            QuickAction.START_OVER -> R.string.quick_start_over
            QuickAction.KEEP_AS_RECOGNIZED -> R.string.quick_as_recognized
            QuickAction.PAUSE -> R.string.quick_pause
            QuickAction.RESUME -> R.string.quick_resume
            QuickAction.OPEN_AMBER -> R.string.quick_open_amber
            QuickAction.STYLE_HERE -> R.string.quick_style_here
            QuickAction.NEXT_STYLE -> R.string.quick_next_style
            QuickAction.APP_PAGE -> R.string.quick_app_page
        },
    )

    /** A place of the quick menu editor: the action's name, the cleanup switch as one entry. */
    fun quickMenuItem(context: Context, item: QuickMenuItem): String = when (item) {
        QuickMenuItem.INSERT_AGAIN -> quickAction(context, QuickAction.INSERT_AGAIN)
        QuickMenuItem.UNDO_LAST -> quickAction(context, QuickAction.UNDO_LAST)
        QuickMenuItem.CLEANUP_HERE -> context.getString(R.string.quick_cleanup_here)
        QuickMenuItem.COPY_LAST -> quickAction(context, QuickAction.COPY_LAST)
        QuickMenuItem.HIDE_HERE -> quickAction(context, QuickAction.HIDE_HERE)
        QuickMenuItem.OPEN_AMBER -> quickAction(context, QuickAction.OPEN_AMBER)
        QuickMenuItem.STYLE_HERE -> quickAction(context, QuickAction.STYLE_HERE)
        QuickMenuItem.NEXT_STYLE -> quickAction(context, QuickAction.NEXT_STYLE)
        QuickMenuItem.APP_PAGE -> quickAction(context, QuickAction.APP_PAGE)
    }

    /** "Formal", "Concise": a style's name, as the app page lists it. */
    fun writingStyle(context: Context, style: WritingStyle): String = context.getString(
        when (style) {
            WritingStyle.AS_SPOKEN -> R.string.writing_as_spoken
            WritingStyle.CASUAL -> R.string.writing_casual
            WritingStyle.FORMAL -> R.string.writing_formal
            WritingStyle.CONCISE -> R.string.writing_concise
        },
    )

    fun download(context: Context, download: ModelDownload): String = when (download) {
        is ModelDownload.Running -> context.getString(R.string.download_running, DownloadNotice.percent(download))
        ModelDownload.WaitingForWifi -> context.getString(R.string.download_waiting_for_wifi)
    }

    /** What a dictation started during the download is told. */
    fun downloadAtStart(context: Context, download: ModelDownload): String =
        context.getString(R.string.download_at_start, download(context, download))

    /** "Amber (debug) is also on - switch one off." - null when there is none. */
    fun otherAmbers(context: Context, others: List<AmberVariant>): String? {
        val names = others.map { it.label }
        return when (names.size) {
            0 -> null
            1 -> context.getString(R.string.other_ambers_one, names[0])
            else -> context.getString(R.string.other_ambers_many, names.dropLast(1).joinToString(", "), names.last())
        }
    }

    fun notOffered(context: Context, by: AmberMachine): String = context.getString(
        when (by) {
            AmberMachine.PC -> R.string.not_offered_by_pc
            AmberMachine.SERVER -> R.string.not_offered_by_server
        },
    )

    /** "the PC", "the server" - inside a sentence. */
    fun machine(context: Context, machine: AmberMachine): String = context.getString(
        when (machine) {
            AmberMachine.PC -> R.string.machine_pc
            AmberMachine.SERVER -> R.string.machine_server
        },
    )

    /**
     * A name or note from :core's catalog of speech recognitions and models, in the phone's
     * language: found by its exact English text among [CATALOG] (values/strings_catalog.xml).
     * Anything else - a name the user gave, a note a server sent - stays as it is.
     */
    fun catalog(context: Context, english: String): String {
        if (english.isBlank()) return english
        val id = catalogIds(context)[english] ?: return english
        return context.getString(id)
    }

    @Volatile private var catalogByEnglish: Map<String, Int>? = null

    private fun catalogIds(context: Context): Map<String, Int> = catalogByEnglish ?: run {
        val english = english(context)
        CATALOG.associateBy { english.getString(it) }.also { catalogByEnglish = it }
    }

    private val CATALOG = listOf(
        R.string.catalog_android_name, R.string.catalog_android_note,
        R.string.catalog_whisper_name, R.string.catalog_whisper_note,
        R.string.catalog_parakeet_name, R.string.catalog_parakeet_note,
        R.string.catalog_tinfoil_name, R.string.catalog_tinfoil_note,
        R.string.catalog_own_server, R.string.catalog_own_server_note,
        R.string.catalog_server_name, R.string.catalog_server_note,
        R.string.catalog_groq_note, R.string.catalog_openai_note,
        R.string.catalog_voxtral, R.string.catalog_tinfoil_whisper, R.string.catalog_gemma,
        R.string.catalog_gpt_oss_120b, R.string.catalog_llama_tinfoil, R.string.catalog_glm,
        R.string.catalog_kimi, R.string.catalog_deepseek, R.string.catalog_groq_turbo,
        R.string.catalog_groq_large, R.string.catalog_groq_llama70, R.string.catalog_groq_llama8,
        R.string.catalog_groq_gpt_oss, R.string.catalog_openai_mini_transcribe,
        R.string.catalog_openai_transcribe, R.string.catalog_openai_whisper, R.string.catalog_nano,
        R.string.catalog_gpt4o_mini, R.string.catalog_gpt41_mini,
        R.string.catalog_claude_haiku, R.string.catalog_claude_sonnet,
        R.string.catalog_futo39_note, R.string.catalog_futo74_note, R.string.catalog_futo244_note,
        R.string.catalog_acft_note, R.string.catalog_old_large_name, R.string.catalog_old_large_note,
        R.string.catalog_tier_fastest, R.string.catalog_tier_fast, R.string.catalog_tier_more_accurate,
        R.string.catalog_tier_most_accurate, R.string.catalog_tier_replaced,
    )

    /**
     * A dictation's words as Diagnostics show them until "Show text": "412 characters · 68 words
     * · 3 lines · German".
     */
    fun shape(context: Context, shape: TextShape): String = with(context) {
        listOfNotNull(
            count(R.plurals.diag_shape_characters, shape.characters),
            count(R.plurals.diag_shape_words, shape.words),
            count(R.plurals.diag_shape_lines, shape.lines),
            when (shape.language) {
                null -> null
                "de" -> getString(R.string.diag_shape_german)
                "en" -> getString(R.string.diag_shape_english)
                else -> shape.language
            },
        ).joinToString(" · ")
    }

    /** "1.5" or "1,5" as the phone writes it. */
    fun decimal(context: Context, value: Double): String = String.format(locale(context), "%.1f", value)

    fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    /**
     * A language's name in the phone's language: "Deutsch", "Englisch" or "German", "English".
     * [withRegion] names the variant a whole tag holds - "Englisch (Vereinigtes Königreich)" -
     * where the variant is what the speech recognition gets (Android's).
     */
    fun languageName(context: Context, tag: String, withRegion: Boolean = false): String {
        val shown = locale(context)
        val language = Locale.forLanguageTag(tag)
        val name = (if (withRegion) language.getDisplayName(shown) else language.getDisplayLanguage(shown)).ifBlank { tag }
        return name.replaceFirstChar { it.titlecase(shown) }
    }

    private fun Context.count(id: Int, n: Int): String = resources.getQuantityString(id, n, n)

    private fun Problem.Payload.pick(recording: Int, text: Int): Int =
        if (this == Problem.Payload.RECORDING) recording else text
}
