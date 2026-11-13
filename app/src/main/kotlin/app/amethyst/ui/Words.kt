package app.amethyst.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.amethyst.Texts
import app.amethyst.core.engine.ConfigIssue
import app.amethyst.core.engine.FileProblem
import app.amethyst.core.onboarding.EngineName
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.settings.Summary
import app.amethyst.core.update.LastCheck
import app.amethyst.core.update.UpdateSummary

/** [Texts] for the screens: the values :core gives, in the phone's language. */
@Composable
fun words(summary: Summary): String = Texts.summary(LocalContext.current, summary)

@Composable
fun words(issue: ConfigIssue): String = Texts.issue(LocalContext.current, issue)

@Composable
fun words(problem: FileProblem): String = Texts.file(LocalContext.current, problem)

@Composable
fun words(name: EngineName): String = Texts.engineName(LocalContext.current, name)

@Composable
fun words(summary: UpdateSummary): String = Texts.updates(LocalContext.current, summary)

@Composable
fun words(check: LastCheck): String = Texts.lastCheck(LocalContext.current, check)

/** A speech recognition's name: a preset's in the phone's language, any other as it is. */
@Composable
fun engineName(engine: EngineConfig): String = Texts.engineName(LocalContext.current, engine)

/** [engineName] for a list that says where it runs beside the name: without the place. */
@Composable
fun recognitionRowName(engine: EngineConfig): String = Texts.recognitionRowName(LocalContext.current, engine)

/** A name or note from :core's catalog in the phone's language; anything else as it is. */
@Composable
fun catalog(english: String): String = Texts.catalog(LocalContext.current, english)
