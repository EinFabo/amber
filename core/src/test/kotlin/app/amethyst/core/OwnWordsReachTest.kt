package app.amethyst.core

import app.amethyst.core.engine.EngineKind
import app.amethyst.core.settings.OwnWordsReach
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnWordsReachTest {

    @Test
    fun `whisper and servers take the own words, parakeet and android cannot`() {
        assertTrue(OwnWordsReach.recognitionTakes(EngineKind.WHISPER_CPP))
        assertTrue(OwnWordsReach.recognitionTakes(EngineKind.OPENAI_COMPATIBLE))
        assertFalse(OwnWordsReach.recognitionTakes(EngineKind.LOCAL_SHERPA))
        assertFalse(OwnWordsReach.recognitionTakes(EngineKind.ANDROID_ON_DEVICE))
    }
}
