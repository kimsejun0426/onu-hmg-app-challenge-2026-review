package dev.carplaymate.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Current exhaustive paths and historical representative paths use independent Python oracles. */
@RunWith(AndroidJUnit4::class)
class AdventureParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun forEachPack(check: (LocalGameEngine, JSONObject) -> Unit) {
        val catalog = JSONObject(instrumentation.context.assets.open("fixtures/catalog-parity.json")
            .bufferedReader().use { it.readText() })
        val current = catalog.getJSONArray("packs").objects()
        val historical = catalog.getJSONArray("legacy_packs").objects()
        assertTrue("Historical sessions need versioned content coverage", historical.isNotEmpty())
        assertEquals("Catalog identities must be version-exact", (current + historical).size,
            (current + historical).map { it.getString("pack_id") to it.getString("pack_version") }.toSet().size)
        assertEquals(ContentReleaseExpectations.currentVersions,
            current.associate { it.getString("pack_id") to it.getString("pack_version") })
        assertEquals(ContentReleaseExpectations.legacyIdentities.toSet(),
            historical.map { it.getString("pack_id") to it.getString("pack_version") }.toSet())
        // Retain only the small catalog index. Returning a mapped list of parsed
        // fixtures kept every edition alive and exhausted the 192 MiB test heap.
        for (entry in current + historical) {
            checkPack(entry, check)
        }
    }

    private fun checkPack(entry: JSONObject, check: (LocalGameEngine, JSONObject) -> Unit) {
        // This non-inline call owns the parsed objects. After it returns no
        // fixture/engine is retained while the next edition is being parsed.
        val source = JSONObject(instrumentation.targetContext.assets.open(entry.getString("content_asset"))
            .bufferedReader().use { it.readText() })
        val fixture = JSONObject(instrumentation.context.assets.open(entry.getString("fixture_asset"))
            .bufferedReader().use { it.readText() })
        assertEquals(entry.getString("pack_id"), source.getString("pack_id"))
        assertEquals(entry.getString("pack_version"), source.getString("version"))
        assertEquals(source.getString("version"), fixture.getString("pack_version"))
        for (section in listOf("paths", "interpretations", "operations")) {
            assertEquals("${entry.getString("pack_id")} ${entry.getString("pack_version")}: $section",
                entry.getInt(section), fixture.getJSONArray(section).length())
        }
        check(LocalGameEngine(source), fixture)
    }

    @Test fun everyCatalogPathMatchesPythonStatePresentationAndActualEnding() {
        forEachPack { rules, fixture ->
            for (path in fixture.getJSONArray("paths").objects()) {
                var state = rules.newGame(path.getJSONArray("players"))
                same(path.getJSONObject("initial_state"), state)
                same(path.getJSONObject("initial_presentation"), rules.present(state))
                for (step in path.getJSONArray("steps").objects()) {
                    state = rules.applyAction(state, step.getString("actor_id"), step.getString("action_id"))
                    same(step.getJSONObject("state"), state)
                    same(step.getJSONObject("presentation"), rules.present(state))
                }
                same(path.getJSONObject("final_state"), state)
                assertTrue(state.getBoolean("finished"))
            }
        }
    }

    @Test fun everyCatalogAliasAttributionRepairAndControlMatchesPython() {
        forEachPack { rules, fixture ->
            for (case in fixture.getJSONArray("interpretations").objects()) {
                same(case.getJSONObject("expected_candidate"), rules.interpret(case.getString("text"), case.getJSONObject("state")).toJson())
            }
        }
    }

    @Test fun everyCatalogHintResourceCostAndRejectedChoiceMatchesPython() {
        forEachPack { rules, fixture ->
            for (case in fixture.getJSONArray("operations").objects()) {
                val state = case.getJSONObject("state")
                val before = canonicalJson(state)
                try {
                    val args = case.getJSONObject("arguments")
                    val result = if (case.getString("kind") == "apply_hint") rules.applyHint(state)
                        else rules.applyAction(state, args.getString("actor_id"), args.getString("action_id"))
                    if (case.has("expected_error")) fail("Expected ${case.getJSONObject("expected_error")}")
                    same(case.getJSONObject("expected_state"), result)
                    same(case.getJSONObject("expected_presentation"), rules.present(result))
                } catch (error: LocalGameError) {
                    assertEquals(case.getJSONObject("expected_error").getString("code"), error.code)
                }
                assertEquals(before, canonicalJson(state))
            }
        }
    }

    @Test fun aNicknameInsideAnExactChoiceDoesNotInventASecondSpeaker() {
        val pack = JSONObject(instrumentation.targetContext.assets.open("content/ko-KR/starlight-post-office/episode.json")
            .bufferedReader().use { it.readText() })
        val rules = LocalGameEngine(pack)
        val state = rules.newGame(playersBody("별", "달").getJSONArray("players")).put("scene_id", "choose_tool")
        val valid = rules.interpret("별, 별 등", state)
        assertEquals("STAR_LANTERN", valid.actionId)
        assertEquals("p1", valid.declaredPlayerId)
        assertFalse(valid.needsClarification)
        assertEquals("wrong_player", rules.interpret("달, 별 등", state).reason)
        assertEquals("multiple_players", rules.interpret("별, 달, 별 등", state).reason)
        assertEquals("multiple_players", rules.interpret("별, 바람종 아니 달, 별 등", state).reason)
        assertTrue("an ambiguous bare action must not claim a turn", rules.interpret("별 등", state).needsClarification)
    }

    private fun same(expected: JSONObject, actual: JSONObject) = assertEquals(canonicalJson(expected), canonicalJson(actual))
}
