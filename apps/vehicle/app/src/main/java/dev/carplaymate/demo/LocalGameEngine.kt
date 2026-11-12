package dev.carplaymate.demo

import android.icu.lang.UCharacter
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

internal fun JSONObject.copyJson() = JSONObject(toString())
internal fun JSONObject.keyList(): List<String> = keys().asSequence().toList()
internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
internal fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)
internal fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

// Python str.isspace()/re \s include the four information separators as well as Unicode
// White_Space. Android uses ICU regex, which does NOT support Java's (?U) flag.
internal const val PYTHON_SPACE_CHARS = "\\u0009-\\u000D\\u001C-\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
private val pythonSpaces = Regex("[$PYTHON_SPACE_CHARS]+")
private val pythonOuterSpaces = Regex("^[$PYTHON_SPACE_CHARS]+|[$PYTHON_SPACE_CHARS]+$")
internal fun pythonWords(value: String): List<String> = value.split(pythonSpaces).filter { it.isNotEmpty() }
internal fun pythonTrim(value: String): String = value.replace(pythonOuterSpaces, "")

internal class LocalGameError(val code: String) : Exception(code)

internal data class LocalCandidate(
    val intent: String = "UNKNOWN",
    val declaredPlayerId: String? = null,
    val actionId: String? = null,
    val needsClarification: Boolean = true,
    val reason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("intent", intent)
        .putNullable("declared_player_id", declaredPlayerId).putNullable("action_id", actionId)
        .put("needs_clarification", needsClarification).putNullable("reason", reason)
}

/** Copy-on-write port of carplay.game. Content owns every scene, choice and score. No IO. */
internal class LocalGameEngine(private val pack: JSONObject) {
    private val scenes = pack.getJSONObject("scenes")
    private val controls = linkedMapOf(
        "PAUSE" to listOf("그만", "그만해", "그만해줘", "그만할래", "그만하자", "이제 그만", "멈춰", "멈춰줘", "조용히", "조용히 해줘", "쉬자", "쉬고 싶어", "쉴래", "졸려", "잠깐", "잠깐만", "일시정지", "하기 싫어", "안 할래", "안 하고 싶어", "그만 하고 싶어", "싫어", "싫어요", "오누야 그만"),
        "RESUME" to listOf("계속", "계속해", "계속하자", "이어하기", "이어서 하자", "다시 하자"),
        "END" to listOf("끝내기", "게임 종료", "놀이 종료", "종료"),
        "REPEAT" to listOf("다시", "그거 다시", "다시 말해줘", "다시 들려줘", "못 들었어", "다시 듣기"),
        "HINT" to listOf("힌트", "도와줘", "모르겠어", "힌트 줘", "잘 모르겠어", "몰라", "잘 몰라", "모르겠다", "모르겠어요"),
        "WAIT" to listOf("생각 중", "생각중", "생각 중이야", "생각중이야", "기다려", "기다려줘", "잠시만 기다려"),
    ).mapValues { (_, values) -> values.map(::compact).toSet() }
    // Keep historical nickname validity when an existing family starts a new
    // edition. New profile entry separately rejects these newly added controls.
    private val recoveryAliases = setOf("하기싫어", "안할래", "안하고싶어", "그만하고싶어", "싫어", "싫어요", "오누야그만",
        "몰라", "잘몰라", "모르겠다", "모르겠어요")
    private val repairs = setOf("그 말 안 했어", "잘못 들었어", "다시 말할게").map(::compact).toSet()
    private val word = "\\p{L}\\p{N}_"
    private val correction = Regex("(?:아니고|아니라|아니(?![$word])|말고)")

    private fun fold(value: String) = UCharacter.foldCase(Normalizer.normalize(value, Normalizer.Form.NFKC), true)
    private fun norm(value: String): String = pythonWords(fold(value).replace(Regex("[^\\p{L}\\p{N}_$PYTHON_SPACE_CHARS]"), " ")).joinToString(" ")
    private fun compact(value: String) = norm(value).replace(" ", "")
    private fun aliases(action: JSONObject): Set<String> =
        (listOf(action.getString("id"), action.getString("label")) + (action.optJSONArray("aliases")?.strings() ?: emptyList()))
            .map(::norm).toSet()

    fun newGame(players: JSONArray): JSONObject {
        if (players.length() !in 1..2) throw LocalGameError("invalid_players")
        val clean = JSONArray()
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        val scores = JSONObject()
        for ((index, player) in players.objects().withIndex()) {
            val id = player.opt("player_id") as? String ?: throw LocalGameError("invalid_players")
            val name = (player.opt("nickname") as? String)?.let(::pythonTrim) ?: throw LocalGameError("invalid_players")
            val normalized = compact(name)
            if (id.isBlank() || id.length > 64 || name.codePointCount(0, name.length) !in 1..12 ||
                normalized.isEmpty() || !ids.add(id) || !names.add(normalized) ||
                (normalized !in recoveryAliases && controls.values.any { normalized in it })) {
                throw LocalGameError("invalid_players")
            }
            val roles = pack.optJSONArray("roles")?.strings() ?: listOf("탐험가", "연구원")
            if (roles.size != 2 || roles.any { it.isBlank() }) throw LocalGameError("invalid_content")
            clean.put(JSONObject().put("player_id", id).put("nickname", name)
                .put("role", if (players.length() == 1) pack.optString("solo_role", "탐험가와 연구원") else roles[index]))
            scores.put(id, 0)
        }
        return JSONObject().put("pack_id", pack.getString("pack_id")).put("pack_version", pack.getString("version"))
            .put("players", clean).put("scene_id", pack.getString("start_scene"))
            .put("expected_player_id", clean.getJSONObject(0).getString("player_id"))
            .put("turn_index", 0).put("resources", pack.getJSONObject("initial_resources").copyJson())
            .put("score_by_player", scores).put("clues", JSONArray()).put("flags", JSONObject())
            .put("hinted_scene_ids", JSONArray()).put("history", JSONArray()).put("feedback", "")
            .put("finished", false).put("ending", JSONObject.NULL)
    }

    private fun scene(state: JSONObject): JSONObject {
        if (state.optString("pack_id") != pack.getString("pack_id") || state.optString("pack_version") != pack.getString("version")) {
            throw LocalGameError("content_version_mismatch")
        }
        return scenes.optJSONObject(state.getString("scene_id")) ?: throw LocalGameError("invalid_state")
    }

    private fun render(text: String, state: JSONObject): String {
        val players = state.getJSONArray("players").objects()
        val player = players.firstOrNull { it.getString("player_id") == state.stringOrNull("expected_player_id") } ?: players.first()
        val scores = state.getJSONObject("score_by_player")
        val values = mutableMapOf(
            "nickname" to player.getString("nickname"), "role" to player.getString("role"),
            "destination_label" to when (state.getJSONObject("flags").optString("egg_location")) {
                "GREENHOUSE" -> "온실"
                "WORKSHOP" -> "공작실"
                else -> "연구소"
            },
            "total_score" to scores.keyList().sumOf { scores.getInt(it) }.toString(),
            "hint_remaining" to state.getJSONObject("resources").optInt("hint_tokens").toString(),
        )
        pack.optJSONObject("template_labels")?.let { labels ->
            for (key in labels.keyList()) {
                if (key in values) throw LocalGameError("invalid_content")
                val definition = labels.getJSONObject(key)
                val flag = state.getJSONObject("flags").optString(definition.getString("flag"))
                values[key] = definition.getJSONObject("values").optString(flag, definition.getString("default"))
            }
        }
        return Regex("\\{([a-z_]+)\\}").replace(text) { values[it.groupValues[1]] ?: throw LocalGameError("invalid_content") }
    }

    fun sceneMetadata(state: JSONObject): JSONObject = scene(state).copyJson()
    fun renderMetadata(text: String, state: JSONObject): String = render(text, state)
    fun actionMetadata(sceneId: String, actionId: String): JSONObject {
        val scene = scenes.optJSONObject(sceneId) ?: throw LocalGameError("invalid_state")
        val action = scene.getJSONArray("actions").objects().firstOrNull { it.getString("id") == actionId }
            ?: throw LocalGameError("invalid_state")
        return JSONObject().put("scene_title", scene.optString("title", pack.getString("title")))
            .put("action_label", action.getString("label"))
    }

    fun present(state: JSONObject): JSONObject {
        val scene = scene(state)
        var prompt = render(scene.getString("prompt"), state)
        if (!state.getBoolean("finished")) {
            val player = state.getJSONArray("players").objects().first { it.getString("player_id") == state.getString("expected_player_id") }
            val soloStyle = pack.optString("solo_prompt_style",
                if (pack.getString("version") == "0.2.0") "nickname" else "role_and_turn")
            val singlePlayer = state.getJSONArray("players").length() == 1
            prompt = when {
                singlePlayer && soloStyle == "body_only" -> prompt
                singlePlayer && soloStyle == "nickname" -> "${player.getString("nickname")}, $prompt"
                else -> "${player.getString("nickname")} ${player.getString("role")} 차례야. $prompt"
            }
        }
        val actions = JSONArray()
        for (action in scene.getJSONArray("actions").objects()) actions.put(JSONObject()
            .put("id", action.getString("id")).put("label", action.getString("label"))
            .put("aliases", JSONArray(action.optJSONArray("aliases")?.toString() ?: "[]")))
        val roles = JSONObject()
        state.getJSONArray("players").objects().forEach { roles.put(it.getString("player_id"), it.getString("role")) }
        return JSONObject().put("scene_id", state.getString("scene_id")).put("prompt", prompt).put("actions", actions)
            .putNullable("expected_player_id", state.stringOrNull("expected_player_id")).put("finished", state.getBoolean("finished"))
            .put("hint", render(scene.optString("hint", ""), state)).put("feedback", state.optString("feedback", ""))
            .putNullable("ending", state.stringOrNull("ending")).put("roles", roles)
    }

    private fun stripPlayer(text: String, players: List<JSONObject>): Pair<String?, String> {
        for (player in players.sortedByDescending { it.getString("nickname").codePointCount(0, it.getString("nickname").length) }) {
            val name = Regex.escape(fold(player.getString("nickname")))
            val match = Regex("^$name(?:(?:이가|이는|이|가|은|는))?(?=$|[$PYTHON_SPACE_CHARS,:：.!?])[$PYTHON_SPACE_CHARS]*[,：:]?[$PYTHON_SPACE_CHARS]*").find(text)
            if (match != null) return player.getString("player_id") to pythonTrim(text.substring(match.range.last + 1))
        }
        return null to text
    }

    private fun mentionedActions(text: String, actions: List<JSONObject>): Set<String> = actions.filter { action ->
        aliases(action).any { alias ->
            Regex("(?<![$word])${Regex.escape(alias)}(?=$|[$PYTHON_SPACE_CHARS]|랑|와|과|하고|이나|또는|으로|로|에|을|를|이야|일까)").containsMatchIn(text)
        }
    }.map { it.getString("id") }.toSet()

    /** Tolerate one short positive reason, without returning or retaining it. */
    private fun choiceWithReason(text: String, actions: List<JSONObject>, players: List<JSONObject>): List<JSONObject> {
        val reason = Regex("(?:(?:나|나는|내가) |왜냐하면 (?:(?:나|나는|내가) )?)(?:[가-힣]{1,12} ){1,3}(?:좋아|좋아해|좋아하거든|좋거든|궁금해|궁금하거든|보고 싶어|재미있어|재미있거든|예뻐|멋져)")
        if (mentionedActions(text, actions).size != 1) return emptyList()
        val blocked = listOf("아니", "말고", "않", "싫", "그만", "취소", "멈", "쉬", "다시", "힌트", "도와", "몰라", "모르", "기다", "계속", "종료", "끝내")
        return actions.filter { action -> aliases(action).any { alias ->
            if (!text.startsWith("$alias ")) false else {
                val tail = text.substring(alias.length + 1)
                val compactTail = tail.replace(" ", "")
                reason.matches(tail) && !Regex("(?:^| )(?:안|못)(?: |$)").containsMatchIn(tail) &&
                    actions.none { other -> other.getString("id") != action.getString("id") &&
                        aliases(other).any { it.replace(" ", "") in compactTail } } &&
                    blocked.none { it in compactTail } &&
                    players.none { compact(it.getString("nickname")) in compactTail }
            }
        } }
    }

    fun interpret(text: String, state: JSONObject): LocalCandidate {
        val scene = scene(state)
        if (pythonTrim(text).isEmpty()) return LocalCandidate(reason = "no_input")
        if (text.codePointCount(0, text.length) > 1000) return LocalCandidate(reason = "input_too_long")
        val folded = pythonTrim(fold(text))
        val players = state.getJSONArray("players").objects()
        var (playerId, body) = stripPlayer(folded, players)
        // A spoken address is only grammar inside an already open answer window.
        // Registered full names win, and ambiguous Onu-like names keep their old meaning.
        var addressed = false
        if (players.none { compact(it.getString("nickname")) in setOf("오누", "오누야") }) {
            val address = Regex("^오누야(?=$|[$PYTHON_SPACE_CHARS,:：.!?])[$PYTHON_SPACE_CHARS,:：.!?]*").find(body)
            if (address != null) {
                addressed = true
                body = pythonTrim(body.substring(address.range.last + 1))
                if (playerId == null) {
                    val declaration = stripPlayer(body, players)
                    playerId = declaration.first
                    body = declaration.second
                }
            }
        }
        val remainingName = if (addressed) stripPlayer(body, players) else null
        if (addressed && (body.any { it in "\"'`“”‘’「」『』«»‹›()[]{}〈〉《》" } ||
                Regex("(?<![$word])오누야(?=$|[$PYTHON_SPACE_CHARS,:：.!?])").containsMatchIn(body))) {
            return LocalCandidate(declaredPlayerId = playerId, reason = "unconfirmed_expansion")
        }
        if (addressed && (norm(body).isEmpty() ||
                (remainingName?.first != null && norm(remainingName.second).isEmpty()))) {
            // A bare address/name must not confirm a previously pending choice.
            return LocalCandidate(declaredPlayerId = playerId, reason = "address_only")
        }
        val actions = scene.getJSONArray("actions").objects()
        val actionAliases = actions.flatMap(::aliases).toSet()
        val greeting = norm(body)
        if (addressed && !state.getBoolean("finished") && greeting !in actionAliases &&
            greeting in setOf("안녕", "뭐해", "뭐 해", "같이놀자", "같이 놀자")) {
            // Reuse one clarification; never turn a corrected suffix into a greeting.
            return LocalCandidate(declaredPlayerId = playerId, reason = "address_only")
        }
        var correctedAction = false
        val corrected = correction.split(body)
        if (addressed && corrected.dropLast(1).any { norm(it) !in actionAliases }) {
            // Do not discard quoted speech, repeated addresses or another owner.
            return LocalCandidate(declaredPlayerId = playerId, reason = "unconfirmed_expansion")
        }
        if (corrected.size > 1 && Regex(" (?:나|나는|내가|왜냐하면) ").containsMatchIn(norm(body))) {
            return LocalCandidate(declaredPlayerId = playerId, reason = "unconfirmed_expansion")
        }
        if (corrected.size > 1) {
            body = corrected.last().trim(' ', ',', '.', '!', '?', '؛', ':')
            // Exact choice text can contain a nickname; it never declares its owner.
            correctedAction = norm(body) in actionAliases
            if (!correctedAction) {
                val (correctedPlayer, correctedBody) = stripPlayer(body, players)
                if (correctedPlayer != null && playerId != null && correctedPlayer != playerId) return LocalCandidate(reason = "multiple_players")
                playerId = correctedPlayer ?: playerId
                body = correctedBody
            }
        }
        val normalized = norm(body)
        controls.entries.firstOrNull { normalized.replace(" ", "") in it.value }?.let {
            return LocalCandidate(it.key, playerId, needsClarification = false)
        }
        if (normalized.replace(" ", "") in repairs) return LocalCandidate(declaredPlayerId = playerId, reason = "repair_requested")
        if (corrected.size > 1 && normalized.isEmpty()) {
            // A retraction cannot confirm the action retained by an earlier name-only prompt.
            return LocalCandidate(declaredPlayerId = playerId, reason = "repair_requested")
        }
        for (discarded in corrected.dropLast(1)) {
            if (norm(discarded) !in actionAliases && mentionedPlayers(discarded, players).any { it != playerId }) {
                return LocalCandidate(declaredPlayerId = playerId, reason = "multiple_players")
            }
        }
        var matches = actions.filter { normalized in aliases(it) }
        // A complete known action may itself contain a registered nickname ("별 등").
        // Only an already-declared owner plus an exact whole action can bypass name counting.
        val exactOwnedAction = playerId != null && matches.size == 1 && !state.getBoolean("finished")
        val mentioned = mentionedPlayers(folded, players).size
        if (!exactOwnedAction && !correctedAction && mentioned > 1) return LocalCandidate(declaredPlayerId = playerId, reason = "multiple_players")
        if (state.getBoolean("finished")) return LocalCandidate(declaredPlayerId = playerId, reason = "game_finished")
        if (normalized.isEmpty()) return LocalCandidate(declaredPlayerId = playerId, reason = "missing_action")
        if (!exactOwnedAction && !correctedAction && stripPlayer(body, players).second != body) return LocalCandidate(declaredPlayerId = playerId, reason = "multiple_players")
        if (matches.isEmpty() && corrected.size == 1) matches = choiceWithReason(normalized, actions, players)
        if (matches.size != 1) {
            val competing = mentionedActions(normalized, actions).size
            val expanded = actions.any { action -> aliases(action).any { normalized.startsWith("$it ") } }
            return LocalCandidate(declaredPlayerId = playerId, reason = if (competing > 1) "overlap" else if (expanded || addressed) "unconfirmed_expansion" else if (matches.size > 1) "ambiguous_action" else "unknown_action")
        }
        val action = matches.single().getString("id")
        if (players.size > 1 && playerId == null) return LocalCandidate("ACTION", actionId = action, reason = "missing_player")
        val effectivePlayer = playerId ?: state.getString("expected_player_id")
        if (effectivePlayer != state.getString("expected_player_id")) return LocalCandidate("ACTION", playerId, action, reason = "wrong_player")
        return LocalCandidate("ACTION", effectivePlayer, action, needsClarification = false)
    }

    private fun mentionedPlayers(text: String, players: List<JSONObject>): Set<String> = players.filter {
        val name = Regex.escape(fold(it.getString("nickname")))
        Regex("(?<![$word])$name(?:(?:이가|이는|이|가|은|는))?(?=$|[$PYTHON_SPACE_CHARS,:：.!?])").containsMatchIn(text)
    }.map { it.getString("player_id") }.toSet()

    fun applyAction(state: JSONObject, actorId: String, actionId: String): JSONObject {
        val scene = scene(state)
        if (state.getBoolean("finished")) throw LocalGameError("game_finished")
        if (actorId != state.getString("expected_player_id")) throw LocalGameError("wrong_player")
        val action = scene.getJSONArray("actions").objects().firstOrNull { it.getString("id") == actionId }
            ?: throw LocalGameError("invalid_action")
        val cost = action.optJSONObject("cost") ?: JSONObject()
        val resources = state.getJSONObject("resources")
        if (cost.keyList().any { resources.optInt(it) < cost.getInt(it) }) throw LocalGameError("insufficient_resource")
        val flags = state.getJSONObject("flags")
        val chosen = (action.optJSONArray("outcomes")?.objects() ?: listOf(action)).firstOrNull { outcome ->
            val condition = outcome.optJSONObject("when") ?: JSONObject()
            condition.keyList().all { flags.opt(it) == condition.get(it) }
        } ?: throw LocalGameError("invalid_content")
        val result = state.copyJson()
        val nextResources = result.getJSONObject("resources")
        cost.keyList().forEach { nextResources.put(it, nextResources.getInt(it) - cost.getInt(it)) }
        val nextFlags = result.getJSONObject("flags")
        val clues = result.getJSONArray("clues").strings().toMutableList()
        for (effect in if (chosen === action) listOf(action) else listOf(action, chosen)) {
            effect.optJSONObject("set_flags")?.let { changes -> changes.keyList().forEach { nextFlags.put(it, changes.get(it)) } }
            effect.optJSONArray("add_clues")?.strings()?.forEach { if (it !in clues) clues.add(it) }
        }
        result.put("clues", JSONArray(clues))
        var score = chosen.optInt("score")
        if (state.getString("scene_id") in state.getJSONArray("hinted_scene_ids").strings()) score = maxOf(0, score - pack.optInt("hint_score_penalty", 1))
        val scores = result.getJSONObject("score_by_player")
        scores.put(actorId, scores.getInt(actorId) + score)
        result.put("feedback", chosen.optString("feedback", ""))
        result.getJSONArray("history").put(JSONObject().put("scene_id", state.getString("scene_id"))
            .put("player_id", actorId).put("action_id", actionId).put("score_delta", score).put("resource_cost", cost.copyJson()))
        val nextSceneId = chosen.getString("next")
        val nextScene = scenes.getJSONObject(nextSceneId)
        val turnIndex = state.getInt("turn_index") + 1
        val finished = nextScene.getString("kind") == "ending"
        result.put("scene_id", nextSceneId).put("turn_index", turnIndex).put("finished", finished)
            .putNullable("ending", nextScene.stringOrNull("ending_id"))
        val players = result.getJSONArray("players")
        result.putNullable("expected_player_id", if (finished) null else players.getJSONObject(turnIndex % players.length()).getString("player_id"))
        return result
    }

    fun applyHint(state: JSONObject): JSONObject {
        val scene = scene(state)
        if (state.getBoolean("finished")) throw LocalGameError("game_finished")
        if (scene.optString("hint", "").isEmpty()) throw LocalGameError("hint_unavailable")
        val result = state.copyJson()
        val hinted = result.getJSONArray("hinted_scene_ids")
        if (state.getString("scene_id") !in hinted.strings()) {
            val resources = result.getJSONObject("resources")
            if (resources.optInt("hint_tokens") < 1) throw LocalGameError("insufficient_resource")
            resources.put("hint_tokens", resources.getInt("hint_tokens") - 1)
            hinted.put(state.getString("scene_id"))
        }
        result.put("feedback", "힌트야. " + render(scene.getString("hint"), result))
        return result
    }
}
