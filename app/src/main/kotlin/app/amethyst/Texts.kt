package app.amethyst

import android.content.Context
import android.content.res.Configuration
import app.amethyst.core.bubble.QuickAction
import app.amethyst.core.privacy.AttestationFailure
import app.amethyst.core.bubble.QuickMenuItem
import app.amethyst.core.bubble.QuickOff
import app.amethyst.core.dictation.ExitKind
import app.amethyst.core.dictation.ExitRecord
import app.amethyst.core.dictation.TextShape
import app.amethyst.core.engine.AmberMachine
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.EnginePreset
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.FallbackNotice
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.engine.HttpReason
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.Problem
import app.amethyst.core.engine.SttFailure
import app.amethyst.core.engine.sherpa.ParakeetModel
import app.amethyst.core.engine.whisper.WhisperModels
import app.amethyst.core.onboarding.AmberVariant
import app.amethyst.core.onboarding.EngineName
import app.amethyst.core.onboarding.RealAppNudge
import app.amethyst.core.onboarding.SwitchOnStep
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.RecognitionRows
import app.amethyst.core.settings.SettingsSummaries
import app.amethyst.core.settings.Summary
import app.amethyst.core.settings.ThemeMode
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.CleanupTrouble
import app.amethyst.core.update.Ago
import app.amethyst.core.update.LastCheck
import app.amethyst.core.update.UpdateSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    /** A step in Android's settings: what to tap, and a line more if there is one. [service] is the name Android lists Embersay's service by. */
    fun switchOnStep(context: Context, step: SwitchOnStep, service: String): Pair<String, String?> = with(context) {
        when (step) {
            SwitchOnStep.INSTALLED_APPS -> getString(R.string.switch_on_installed) to getString(R.string.switch_on_installed_note)
            SwitchOnStep.DOWNLOADED_APPS -> getString(R.string.switch_on_downloaded) to getString(R.string.switch_on_downloaded_note)
            SwitchOnStep.TAP_SERVICE -> getString(R.string.switch_on_step1, service) to getString(R.string.switch_on_step1_note)
            SwitchOnStep.TURN_ON -> getString(R.string.switch_on_step2, service) to null
            SwitchOnStep.ALLOW -> getString(R.string.switch_on_step3) to getString(R.string.switch_on_step3_note)
            SwitchOnStep.APP_INFO -> getString(R.string.battery_step_app_info) to getString(R.string.battery_step_app_info_note)
            SwitchOnStep.TAP_BATTERY -> getString(R.string.battery_step_battery) to null
            SwitchOnStep.UNRESTRICTED -> getString(R.string.battery_step_unrestricted) to getString(R.string.battery_step_unrestricted_note)
            SwitchOnStep.BATTERY_OPTIMIZATION -> getString(R.string.battery_step_optimization) to getString(R.string.battery_step_list_note)
            SwitchOnStep.ALL_APPS -> getString(R.string.battery_step_all_apps) to getString(R.string.battery_step_all_apps_note)
            SwitchOnStep.DONT_OPTIMIZE -> getString(R.string.battery_step_dont_optimize) to getString(R.string.battery_step_unrestricted_note)
            SwitchOnStep.OPTIMIZE_BATTERY_USAGE -> getString(R.string.battery_step_optimize_usage) to getString(R.string.battery_step_list_note)
            SwitchOnStep.ALL_SAMSUNG -> getString(R.string.battery_step_all_samsung) to getString(R.string.battery_step_all_apps_note)
            SwitchOnStep.SWITCH_OFF_OPTIMIZING -> getString(R.string.battery_step_switch_off) to getString(R.string.battery_step_unrestricted_note)
            SwitchOnStep.NEVER_SLEEPING -> getString(R.string.battery_step_never_sleeping) to getString(R.string.battery_step_never_sleeping_note)
            SwitchOnStep.AUTOSTART -> getString(R.string.battery_step_autostart) to getString(R.string.battery_step_autostart_note)
        }
    }

    fun attestation(context: Context, failure: AttestationFailure?): String = with(context) {
        when (failure) {
            AttestationFailure.InvalidAddress -> getString(R.string.attestation_invalid_address)
            is AttestationFailure.UnknownEnclave -> getString(R.string.attestation_unknown_enclave, failure.host)
            AttestationFailure.LibraryMissing -> getString(R.string.attestation_library_missing)
            AttestationFailure.DocumentInvalid -> getString(R.string.attestation_document_invalid)
            AttestationFailure.ReleaseNotConfirmed -> getString(R.string.attestation_release_not_confirmed)
            AttestationFailure.HardwareRejected -> getString(R.string.attestation_hardware_rejected)
            AttestationFailure.PolicyViolated -> getString(R.string.attestation_policy_violated)
            AttestationFailure.Unavailable -> getString(R.string.attestation_unavailable)
            AttestationFailure.Failed, null -> getString(R.string.attestation_failed)
        }
    }

    fun problem(context: Context, problem: Problem): String = with(context) {
        when (problem) {
            Problem.NothingRecognized -> getString(R.string.problem_nothing_recognized)
            is Problem.NoApiKey -> getString(R.string.problem_no_api_key, catalog(this, problem.service))
            is Problem.AnswerUnreadable -> getString(R.string.problem_answer_unreadable, catalog(this, problem.service))
            is Problem.NoConnection -> getString(R.string.problem_no_connection, catalog(this, problem.service))
            is Problem.NoAnswerInTime -> getString(R.string.problem_no_answer_in_time, catalog(this, problem.service))
            is Problem.KeyRejected -> getString(R.string.problem_key_rejected, catalog(this, problem.service))
            Problem.AddressNotFound -> getString(R.string.problem_address_not_found)
            is Problem.ModelNotOnServer -> getString(R.string.problem_model_not_on_server, catalog(this, problem.service), problem.model)
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
            Problem.ModelDamaged -> getString(R.string.problem_model_damaged)
            Problem.PickedModelDamaged -> getString(R.string.problem_picked_model_damaged)
            Problem.ModelBeingChecked -> getString(R.string.problem_model_being_checked)
            Problem.NotSupported -> getString(R.string.problem_not_supported)
            is Problem.NotReady -> getString(
                R.string.problem_not_ready,
                catalog(this, problem.service),
                problem.issues.joinToString(" ") { issue(this, it) },
            )
            Problem.Cancelled -> getString(R.string.problem_cancelled)
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
            is Problem.CleanupFailed -> getString(R.string.problem_cleanup_failed, httpReason(this, HttpReason.of(problem.code)), problem.code)
            is Problem.CleanupDeclined -> getString(R.string.problem_cleanup_declined, catalog(this, problem.service))
            is Problem.NoCredit -> getString(R.string.problem_no_credit, catalog(this, problem.service))
            is Problem.CleanupModelUnknown -> getString(R.string.problem_cleanup_model_unknown, problem.model)
            Problem.CleanupUnreadable -> getString(R.string.problem_cleanup_unreadable)
            Problem.CleanupCutOff -> getString(R.string.problem_cleanup_cut_off)
            is Problem.CleanupChangedTooMuch -> getString(R.string.problem_cleanup_changed_too_much)
            Problem.NoSystemRecognizer -> getString(R.string.problem_no_system_recognizer)
            Problem.LanguageNotInstalled -> getString(R.string.problem_language_not_installed)
            Problem.MicrophoneUnreadable -> getString(R.string.problem_microphone_unreadable)
            Problem.MicrophoneTaken -> getString(R.string.problem_microphone_taken)
            Problem.MicrophonePermissionMissing -> getString(R.string.problem_microphone_permission_missing)
            Problem.RecognizerOffline -> getString(R.string.problem_recognizer_offline)
            Problem.NoSpeechHeard -> getString(R.string.problem_no_speech_heard)
            Problem.RecognizerBusy -> getString(R.string.problem_recognizer_busy)
            Problem.RecognizerCrashed -> getString(R.string.problem_recognizer_crashed)
            Problem.RecognitionFailed -> getString(R.string.problem_recognition_failed)
        }
    }

    /** The toast after a refused key whose recording is kept: the fix first, then "Try again". */
    fun keyRejectedTryAgain(context: Context, problem: Problem.KeyRejected): String =
        context.getString(R.string.toast_key_rejected_try_again, catalog(context, problem.service))

    /** What an HTTP status means, in a few words to go before its code: "API key rejected (error 401)". */
    private fun httpReason(context: Context, reason: HttpReason): String = context.getString(
        when (reason) {
            HttpReason.KEY_REFUSED -> R.string.http_reason_key_refused
            HttpReason.NO_CREDIT -> R.string.http_reason_no_credit
            HttpReason.NOT_FOUND -> R.string.http_reason_not_found
            HttpReason.TOO_LARGE -> R.string.http_reason_too_large
            HttpReason.TOO_MANY -> R.string.http_reason_too_many
            HttpReason.TOO_SLOW -> R.string.http_reason_too_slow
            HttpReason.SERVER_DOWN -> R.string.http_reason_server_down
            HttpReason.REFUSED -> R.string.http_reason_refused
        },
    )

    /** What keeps the cleanup from running, in a few words: "Groq key refused". */
    fun cleanupTrouble(context: Context, open: CleanupTrouble.Open): String = context.getString(
        when (open.cause) {
            CleanupTrouble.Cause.KEY_REFUSED -> R.string.cleanup_trouble_key
            CleanupTrouble.Cause.NO_CREDIT -> R.string.cleanup_trouble_credit
            CleanupTrouble.Cause.UNKNOWN_MODEL -> R.string.cleanup_trouble_model
        },
        catalog(context, open.name),
    )

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
            is FileProblem.ServerAnswered -> getString(
                R.string.file_server_answered,
                httpReason(this, HttpReason.forDownload(problem.code)),
                problem.code,
            )
            FileProblem.EmptyAnswer -> getString(R.string.file_empty_answer)
            FileProblem.NoConnection -> getString(R.string.file_no_connection)
            FileProblem.CannotOpen -> getString(R.string.file_cannot_open)
            FileProblem.TooSmallForWhisper -> getString(R.string.file_too_small_for_whisper)
            FileProblem.NotWhisperCpp -> getString(R.string.file_not_whisper_cpp)
            FileProblem.NotWhisper -> getString(R.string.file_not_whisper)
            FileProblem.CutShort -> getString(R.string.file_cut_short)
            is FileProblem.TooLarge -> getString(R.string.file_too_large, problem.maxMb)
            FileProblem.Damaged -> getString(R.string.file_model_damaged)
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
            is Summary.Apps -> listOfNotNull(
                summary.styles.takeIf { it > 0 }?.let { count(R.plurals.summary_styles, it) },
                summary.hidden.takeIf { it > 0 }?.let { count(R.plurals.summary_hidden, it) },
            ).joinToString(" · ").ifEmpty { getString(R.string.summary_apps_none) }
            is Summary.AppCleanupModel -> when {
                summary.own -> summary.model?.let { getString(R.string.summary_own_model, it) } ?: getString(R.string.style_own_model)
                else -> summary.model?.let { getString(R.string.summary_default_model, it) } ?: getString(R.string.summary_default_off)
            }
            Summary.NotNeeded -> getString(R.string.summary_not_needed)
            is Summary.Fallback -> {
                val who = summary.engine?.let { engineShortName(this, it) } ?: getString(R.string.summary_fallback_automatic)
                if (summary.prepared) getString(R.string.summary_prepared, who) else who
            }
            is Summary.Bubble -> listOfNotNull(
                getString(if (summary.onlyWithField) R.string.summary_bubble_field else R.string.summary_bubble_always),
                summary.hidden.takeIf { it > 0 }?.let { count(R.plurals.summary_bubble_hidden, it) },
            ).joinToString(" · ")
            is Summary.OwnWords -> if (summary.count == 0) getString(R.string.summary_apps_none) else count(R.plurals.summary_words, summary.count)
            is Summary.StopsAfter -> getString(R.string.summary_stops_after, decimal(this, summary.seconds))
            Summary.RunsUntilTap -> getString(R.string.summary_runs_until_tap)
            is Summary.Text -> summary.first.joinToString(", ") { textPart(this, it) }
                .replaceFirstChar { it.titlecase(locale(this)) }
                .let { parts -> if (summary.more) getString(R.string.summary_titles_and_more, parts) else parts }
                .ifEmpty { getString(R.string.summary_as_recognized) }
            is Summary.Reliability -> if (summary.open == 0) getString(R.string.summary_all_set) else count(R.plurals.summary_things_open, summary.open)
            is Summary.Diagnostics -> if (summary.count == 0) getString(R.string.summary_nothing_dictated) else count(R.plurals.summary_last_dictations, summary.count)
            // One line on top of Settings, in German too: "Zuverlässigkeit: 2 Punkte offen" took two.
            is Summary.ReliabilityOpen -> count(R.plurals.summary_reliability_open, summary.open)
            is Summary.Models -> count(R.plurals.summary_models, summary.count)
            is Summary.Privacy -> if (summary.secure) {
                getString(R.string.summary_privacy_secure)
            } else {
                getString(R.string.summary_privacy_history, historyKeep(this, summary.keep))
            }
            is Summary.BubbleSize -> getString(
                when (summary.size) {
                    Summary.BubbleSize.Size.SMALL -> R.string.bubble_size_small
                    Summary.BubbleSize.Size.MEDIUM -> R.string.bubble_size_medium
                    Summary.BubbleSize.Size.LARGE -> R.string.bubble_size_large
                    Summary.BubbleSize.Size.EXTRA_LARGE -> R.string.bubble_size_extra_large
                },
            )
            is Summary.Theme -> theme(this, summary.mode)
            is Summary.History -> if (summary.secure) getString(R.string.history_off_secure) else historyKeep(this, summary.keep)
            is Summary.About -> when (val newer = summary.newer) {
                Summary.About.Newer.Unknown -> getString(R.string.summary_version, summary.version)
                Summary.About.Newer.None -> getString(R.string.summary_version_up_to_date, summary.version)
                is Summary.About.Newer.Out -> getString(R.string.summary_version_newer, summary.version, newer.version)
            }
        }
    }

    private fun textPart(context: Context, part: Summary.Text.Part): String = context.getString(
        when (part) {
            Summary.Text.Part.FILLERS -> R.string.text_part_fillers
            Summary.Text.Part.COMMAS -> R.string.text_part_commas
            Summary.Text.Part.SPACES -> R.string.text_part_spaces
            Summary.Text.Part.CAPITALS -> R.string.text_part_capitals
            Summary.Text.Part.PUNCTUATION -> R.string.text_part_punctuation
            Summary.Text.Part.CLIPBOARD -> R.string.text_part_clipboard
        },
    )

    /** How long the history keeps a dictation, as its choice says it. */
    fun historyKeep(context: Context, keep: app.amethyst.core.history.HistoryKeep): String = context.getString(
        when (keep) {
            app.amethyst.core.history.HistoryKeep.OFF -> R.string.off
            app.amethyst.core.history.HistoryKeep.DAY -> R.string.history_keep_day
            app.amethyst.core.history.HistoryKeep.WEEK -> R.string.history_keep_week
        },
    )

    private fun theme(context: Context, mode: ThemeMode): String = context.getString(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
        },
    )

    /** Where the voice goes, in a word or two: the badge's words, so one word names a level everywhere. */
    fun place(context: Context, trust: TrustLevel): String = context.getString(
        when (trust) {
            TrustLevel.ON_DEVICE -> R.string.trust_on_device
            TrustLevel.ENCLAVE_VERIFIED -> R.string.trust_enclave_verified
            TrustLevel.CLOUD -> R.string.trust_cloud
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

    /**
     * The toast when [by] recognized a recording [from] did not ([FallbackNotice]). A server
     * that cannot serve is named with what it said, short: only the user can put it right.
     */
    fun fallbackNotice(context: Context, notice: FallbackNotice, by: EngineConfig, from: EngineConfig?): String = with(context) {
        val name = engineName(this, by)
        when (notice) {
            FallbackNotice.ServerTooSlow -> getString(R.string.fallback_server_too_slow)
            is FallbackNotice.ServerRefused -> when (val said = notice.problem) {
                is Problem.NoCredit -> getString(R.string.fallback_no_credit, catalog(this, said.service), name)
                is Problem.ModelNotOnServer -> getString(R.string.fallback_model_gone, catalog(this, said.service), name)
                else -> problem(this, said)
            }
            FallbackNotice.NoConnection -> getString(R.string.fallback_no_connection, name)
            FallbackNotice.NoAnswerOnPhone -> getString(R.string.fallback_no_answer_on_phone)
            // Only after a server failed, where the server it stood in for is known.
            FallbackNotice.OtherServer -> getString(R.string.fallback_other_server, from?.let { engineName(this, it) }.orEmpty(), name)
            // Only after a model on the phone failed, which is the one stood in for. Both short:
            // "Parakeet", "Whisper local" - two full names would not fit in two lines.
            FallbackNotice.ModelOnPhoneFailed ->
                getString(R.string.fallback_model_on_phone_failed, from?.let { engineShortName(this, it) }.orEmpty(), engineShortName(this, by))
        }
    }

    /**
     * A speech recognition in a list that says where it runs beside it ([RecognitionRows.name]):
     * "Parakeet", "Tinfoil" - the name without the place.
     */
    fun recognitionRowName(context: Context, engine: EngineConfig): String = when (val name = RecognitionRows.name(engine)) {
        is EngineName.Own -> engineShortName(context, name.engine)
        else -> engineName(context, name)
    }

    /**
     * What "Add speech recognition" says about an offered [preset] in one plain line: where it
     * runs, how big, what it needs. The technical notes ([EnginePreset.note]) are on its own page.
     */
    fun presetLine(context: Context, preset: EnginePreset): String = when (preset.id) {
        EnginePresets.ANDROID_ON_DEVICE.id -> context.getString(R.string.add_recognition_android)
        // The sizes come from what the download fetches, so the line cannot drift from it.
        EnginePresets.LOCAL_WHISPER.id -> context.getString(
            R.string.add_recognition_whisper,
            megabytes(WhisperModels.byId(EnginePresets.LOCAL_WHISPER.modelId)?.sizeBytes ?: WhisperModels.FUTO_MULTILINGUAL_74.sizeBytes),
        )
        EnginePresets.LOCAL_PARAKEET.id -> context.getString(R.string.add_recognition_parakeet, megabytes(ParakeetModel.TOTAL_BYTES))
        EnginePresets.TINFOIL.id -> context.getString(R.string.add_recognition_tinfoil)
        else -> context.getString(R.string.add_recognition_server)
    }

    private fun megabytes(bytes: Long): Int = ((bytes + 500_000L) / 1_000_000L).toInt()

    fun quickAction(context: Context, action: QuickAction): String = context.getString(
        when (action) {
            QuickAction.INSERT_AGAIN -> R.string.quick_insert_again
            QuickAction.TRY_AGAIN -> R.string.quick_try_again
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

    /** Why a grey button of the quick menu cannot run ([QuickOff]), when the thumb lets go on it. */
    fun quickOff(context: Context, why: QuickOff): String = context.getString(
        when (why) {
            QuickOff.OTHER_APP_ONLY -> R.string.quick_off_other_app
            QuickOff.NO_DICTATION -> R.string.quick_off_no_dictation
            QuickOff.NOTHING_TO_UNDO -> R.string.quick_off_nothing_to_undo
            QuickOff.NO_FIELD -> R.string.quick_off_no_field
            QuickOff.NO_CLEANUP -> R.string.quick_off_no_cleanup
            QuickOff.CLEANUP_OFF_HERE -> R.string.quick_off_cleanup_off_here
            QuickOff.NOT_FOR_COMMAND -> R.string.quick_off_command
            QuickOff.CANNOT_PAUSE -> R.string.quick_off_cannot_pause
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
        ModelDownload.Retrying -> context.getString(R.string.download_retrying)
    }

    /** "Open WhatsApp and tap a text field - the bubble appears there", or any app. */
    fun realAppNudge(context: Context, nudge: RealAppNudge): String =
        nudge.appName?.let { context.getString(R.string.nudge_app, it) } ?: context.getString(R.string.nudge_any_app)

    /** What a dictation started during the download is told. */
    fun downloadAtStart(context: Context, download: ModelDownload): String =
        context.getString(R.string.download_at_start, download(context, download))

    /** "Embersay (debug)", "Amber (old app)": another build on the phone, by name. */
    fun variant(context: Context, variant: AmberVariant): String {
        val build = variant.buildType
        return when {
            variant.old && build == null -> context.getString(R.string.variant_old)
            variant.old -> context.getString(R.string.variant_old_build, build)
            build == null -> context.getString(R.string.app_name)
            else -> context.getString(R.string.variant_build, build)
        }
    }

    /** The button to the old app's app info: "Open app info", or with its name when there are two. */
    fun uninstallOld(context: Context, variant: AmberVariant, named: Boolean): String =
        if (named) context.getString(R.string.open_app_info_of, variant(context, variant)) else context.getString(R.string.open_app_info)

    /** "Embersay (debug) is also on - switch one off." - null when there is none. */
    fun otherAmbers(context: Context, others: List<AmberVariant>): String? {
        val names = others.map { variant(context, it) }
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

    /**
     * A time the app stopped unexpectedly, as Diagnostics list it: "Embersay stopped unexpectedly
     * at 14:02 (out of memory)" - with its day when that was not [nowMs]'s day in [zone].
     */
    fun exit(context: Context, record: ExitRecord, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = with(context) {
        val locale = locale(this)
        val at = Instant.ofEpochMilli(record.atMs).atZone(zone)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
        val why = getString(
            when (record.kind) {
                ExitKind.CRASH -> R.string.diag_exit_crash
                ExitKind.NATIVE_CRASH -> R.string.diag_exit_native_crash
                ExitKind.ANR -> R.string.diag_exit_anr
                ExitKind.LOW_MEMORY -> R.string.diag_exit_low_memory
                ExitKind.OTHER -> R.string.diag_exit_other
            },
        )
        if (at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()) {
            getString(R.string.diag_exit_today, time, why)
        } else {
            // The short day ("24 Nov", "24. Nov."): the line stays readable on a narrow screen.
            val day = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale).format(at)
            getString(R.string.diag_exit_on_day, day, time, why)
        }
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
