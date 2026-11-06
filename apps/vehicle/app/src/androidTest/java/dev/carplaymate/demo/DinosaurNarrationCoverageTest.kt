package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored text coverage only. No microphone, ASR result, or listening-quality claim. */
@RunWith(AndroidJUnit4::class)
class DinosaurNarrationCoverageTest {
    @Test fun commonGuidanceAndWelcomeResolveToExactSingleBundledClips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = loadNarrationAssets(context)
        assertNull(assets.failure)
        val expected = listOf(
            "천천히 생각해도 괜찮아." to "onu_02f1703fbae8c937",
            "둘이 함께할 때는 내 이름을 먼저 말하고, 고른 말을 이어서 말해 줘." to "onu_2b7b75a34f51025e",
            "응, 여기 있어." to "onu_592e066f5753dfcf",
            "어느 쪽인지 내가 잘 모르겠어. 다시 물어볼게." to "onu_8688d8bb463f9b8e",
            "천천히 생각해도 괜찮아. 누구의 선택인지 이름만 말해 줄래?" to "onu_8a2aa0dc157d243e",
            "아직 선택하지 않았어. 원하는 선택을 다시 말해 줘." to "onu_8f8aac8ebce45da4",
            "그 말은 내가 잘 이해하지 못했어. 다시 물어볼게." to "onu_a29b4330cfc58198",
            "무엇을 할지는 들었어. 누구의 선택인지 이름만 말해 줄래?" to "onu_c89f2f9319ad37d2",
            WelcomeVoicePreview.TEXT to "onu_welcome_b2",
        )
        for ((text, id) in expected) {
            val parts = assets.narration.resolve(text)
            assertEquals("A fixed prompt must not split into device TTS or a cue: $id", 1, parts.size)
            val part = parts.single() as? NarrationPart.Audio
            assertNotNull("Missing exact local narration: $id", part)
            assertEquals(text, part!!.spokenText)
            assertEquals(id, part.audio.id)
            assertEquals("story_audio/$id.wav", part.audio.asset)
        }
        val welcome = WelcomeVoicePreview.greetingFrom(assets)
        assertNotNull(welcome)
        assertEquals("onu_welcome_b2", welcome!!.id)
    }

    @Test fun everyCurrentDinosaurBranchHasBundledBodyHintFeedbackAndEndingForOneOrTwoChildren() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = loadNarrationAssets(context)
        assertNull(library.failure)
        assertFalse(library.narration.isEmpty)
        val rules = LocalAdventureLibrary(context).rules(DEFAULT_ADVENTURE_ID)
        val unique = mutableSetOf<String>()
        fun checkBody(text: String) {
            if (text.isBlank()) return
            unique += text
            val parts = library.narration.resolve(text)
            assertTrue("An authored sentence fell back to device TTS: $text",
                parts.filterIsInstance<NarrationPart.Text>().all { it.text.isBlank() })
            assertEquals(listOf(text), parts.filterIsInstance<NarrationPart.Audio>().mapNotNull { it.spokenText })
        }
        for (second in listOf(null, "별이")) {
            var completed = 0
            fun visit(state: JSONObject) {
                val presentation = rules.present(state)
                val body = rules.renderMetadata(rules.sceneMetadata(state).getString("prompt"), state)
                checkBody(body)
                checkBody(presentation.getString("hint"))
                checkBody(presentation.getString("feedback"))
                val prompt = presentation.getString("prompt")
                val resolved = library.narration.resolve(prompt)
                val dynamicText = resolved.filterIsInstance<NarrationPart.Text>().joinToString("") { it.text }
                // User-chosen nicknames/roles remain dynamic, never a recorded sample identity.
                assertEquals(prompt.removeSuffix(body), dynamicText)
                assertEquals(listOf(body), resolved.filterIsInstance<NarrationPart.Audio>().mapNotNull { it.spokenText })
                if (state.getBoolean("finished")) {
                    assertEquals(6, state.getJSONArray("history").length())
                    completed++
                } else {
                    for (action in presentation.getJSONArray("actions").objects()) {
                        visit(rules.applyAction(state, state.getString("expected_player_id"), action.getString("id")))
                    }
                }
            }
            visit(rules.newGame(playersBody("가람", second).getJSONArray("players")))
            assertEquals("All six-choice branches must be traversed for each player count", 64, completed)
        }
        assertEquals("Current dinosaur authored corpus changed; review complete audio coverage", 44, unique.size)
    }
}
