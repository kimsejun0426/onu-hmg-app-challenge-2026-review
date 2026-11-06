package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Expectations are exported by the independent Python engine, not by the Kotlin port. */
@RunWith(AndroidJUnit4::class)
class LocalGameParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy {
        JSONObject(instrumentation.context.assets.open("fixtures/game-parity.json").bufferedReader().use { it.readText() })
    }
    private val rules by lazy {
        LocalGameEngine(JSONObject(instrumentation.targetContext.assets.open("content/ko-KR/dinosaurs/episode.json")
            .bufferedReader().use { it.readText() }))
    }

    @Test fun all128CompletePathsMatchPythonStateAndPresentation() {
        val paths = fixture.getJSONArray("paths").objects()
        assertEquals(128, paths.size)
        for (path in paths) {
            val id = path.getString("id")
            var state = rules.newGame(path.getJSONArray("players"))
            same("$id initial", path.getJSONObject("initial_state"), state)
            same("$id initial presentation", path.getJSONObject("initial_presentation"), rules.present(state))
            for (step in path.getJSONArray("steps").objects()) {
                val before = canonicalJson(state)
                val next = rules.applyAction(state, step.getString("actor_id"), step.getString("action_id"))
                assertEquals("$id must not mutate input", before, canonicalJson(state))
                state = next
                same("$id ${step.getString("action_id")}", step.getJSONObject("state"), state)
                same("$id presentation", step.getJSONObject("presentation"), rules.present(state))
            }
            same("$id final", path.getJSONObject("final_state"), state)
        }
    }

    @Test fun aliasesCorrectionsAttributionAndControlsMatchPython() {
        val cases = fixture.getJSONArray("interpretations").objects()
        assertEquals(1261, cases.size)
        for (case in cases) {
            val state = case.getJSONObject("state")
            val before = canonicalJson(state)
            val actual = rules.interpret(case.getString("text"), state).toJson()
            same(case.getString("id"), case.getJSONObject("expected_candidate"), actual)
            assertEquals("interpret must not mutate state", before, canonicalJson(state))
        }
    }

    @Test fun hintsResourcesScoresAndRejectedActionsMatchPython() {
        val cases = fixture.getJSONArray("operations").objects()
        assertEquals(82, cases.size)
        for (case in cases) {
            val state = case.getJSONObject("state")
            val before = canonicalJson(state)
            try {
                val args = case.getJSONObject("arguments")
                val next = if (case.getString("kind") == "apply_hint") rules.applyHint(state)
                else rules.applyAction(state, args.getString("actor_id"), args.getString("action_id"))
                if (case.has("expected_error")) fail("${case.getString("id")} expected rejection")
                same(case.getString("id"), case.getJSONObject("expected_state"), next)
                same(case.getString("id"), case.getJSONObject("expected_presentation"), rules.present(next))
            } catch (error: LocalGameError) {
                assertEquals(case.getString("id"), case.getJSONObject("expected_error").getString("code"), error.code)
            }
            assertEquals("${case.getString("id")} must not mutate input", before, canonicalJson(state))
        }
    }

    private fun same(label: String, expected: JSONObject, actual: JSONObject) =
        assertEquals(label, canonicalJson(expected), canonicalJson(actual))
}
