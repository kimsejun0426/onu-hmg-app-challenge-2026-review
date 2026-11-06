package dev.carplaymate.demo

import android.content.Context
import android.icu.lang.UCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class LocalOverview(
    val snapshot: Snapshot?,
    val journal: List<FamilyJournalEntry>,
    val savedAdventures: List<SavedAdventure>,
    val feedback: List<AdventureFeedbackEntry>,
)

/**
 * Bundled, network-free game coordinator. Every operation (including asset reads, parsing,
 * rules, hashing and fsync) runs on Dispatchers.IO. The app uses one process; a shared mutex
 * serializes all instances for this store. SQLite commits encrypted state AND receipts together.
 * The private no-backup records contain authored state and hashes, never utterances or tokens.
 */
class LocalGameClient(
    context: Context,
    private val storageName: String = "local_game_v1",
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : GameClient {
    private val application = context.applicationContext
    private val library = LocalAdventureLibrary(application)
    internal val recordStore by lazy { GameRecordStore(application, storageName, ::validateLegacyStore) }
    init { require(Regex("[A-Za-z0-9_-]{1,80}").matches(storageName)) }

    override suspend fun create(players: JSONObject): Snapshot = createAdventure(players, DEFAULT_ADVENTURE_ID)

    override suspend fun createAdventure(players: JSONObject, packId: String): Snapshot =
        createFamilyAdventure(players, packId, emptyMap())

    suspend fun createFamilyAdventure(players: JSONObject, packId: String, profileIds: Map<String, String>): Snapshot = mutation { store ->
        migrateLegacySelection(store)
        val card = library.card(packId)
        val rules = library.rules(packId)
        val cleanPlayers = validatePlayers(players)
        if (profileIds.isNotEmpty() && (profileIds.keys != cleanPlayers.objects().map { it.getString("player_id") }.toSet() ||
            profileIds.values.toSet().size != profileIds.size || profileIds.values.any { !Regex("[A-Za-z0-9_-]{1,64}").matches(it) })) invalid()
        val state = JSONObject().put("session_id", uid()).put("version", 1).put("last_seq", 1)
            .put("turn_id", uid()).put("turn_epoch", 1).put("status", "ACTIVE").put("phase", "PROMPTING")
            .put("pause_reason", JSONObject.NULL).put("requires_explicit_resume", false)
            .put("listen_window_id", JSONObject.NULL).put("speech_plan", JSONObject.NULL)
            .put("playback_checkpoint", JSONObject.NULL).put("game", rules.newGame(cleanPlayers))
            .put("_captures", JSONArray()).put("_clarifications", 0).put("_recovery_requests", 0).put("_scene_feedback", "")
        plan(state, rules, prefix = if (cleanPlayers.length() == 2)
            "둘이 함께할 때는 내 이름을 먼저 말하고, 고른 말을 이어서 말해 줘." else "")
        val id = state.getString("session_id")
        val created = nowMillis()
        // A confirmed fresh start replaces this pack's unfinished attempt in the same commit.
        // Keep its state/receipts for idempotency, but never resurrect it after the new ending.
        for (oldId in store.sessionIds()) {
            val old = store.readSession(oldId) ?: throw JSONException("Invalid stored session")
            val oldState = old.getJSONObject("state")
            if (oldState.getString("status") != "ENDED" && oldState.getJSONObject("game").getString("pack_id") == packId) {
                val metadata = old.optionalObject("metadata") ?: JSONObject().also { old.put("metadata", it) }
                metadata.put("superseded_by", id)
                store.putSession(oldId, old)
            }
        }
        store.putSession(id, JSONObject().put("state", state).put("receipts", JSONObject())
            .put("metadata", JSONObject().put("created_at_ms", created).put("updated_at_ms", created)
                .put("title", card.title).put("art_key", card.artKey)
                .put("participant_profile_ids", JSONObject(profileIds))))
        store.putRoot(store.root.put("last_session_id", id))
        StoreChange(library.decorate(Snapshot.from(publicState(state, rules))), changed = true)
    }

    override suspend fun snapshot(id: String): Snapshot = transaction { store ->
        val state = read(store, id).getJSONObject("state")
        library.decorate(Snapshot.from(publicState(state, library.rulesFor(state.getJSONObject("game")))))
    }

    internal suspend fun snapshotDocument(id: String): JSONObject = transaction { store ->
        val state = read(store, id).getJSONObject("state")
        publicState(state, library.rulesFor(state.getJSONObject("game")))
    }

    override suspend fun receipt(session: String, command: String): JSONObject = transaction { store ->
        read(store, session)
        lookup(store, session, command) ?: throw ApiException(404, "COMMAND_NOT_FOUND")
    }

    suspend fun adventures(): List<AdventureCard> = withLibrary { it.cards() }

    suspend fun decorateSnapshot(snapshot: Snapshot): Snapshot = withLibrary { it.decorate(snapshot) }

    /** Derived from the same durable commits as gameplay; no second counter can miss a crash. */
    suspend fun completedAdventures(): Int = transaction { store ->
        store.sessionIds().count { id ->
            val state = (store.readSession(id) ?: throw JSONException("Invalid stored session")).getJSONObject("state")
            state.getString("status") == "ENDED" && state.getJSONObject("game").getBoolean("finished")
        }
    }

    /** Independent of UI checkpoints. Reopening never grants permission to play or record. */
    suspend fun restore(sessionId: String? = null): Snapshot? = mutation(recovery = true) { store ->
        restoreFrom(store, sessionId)
    }

    /** Commit quiet recovery before projecting lists, just as restore() followed by the queries did. */
    internal suspend fun restoreAndOverview(sessionId: String? = null): LocalOverview =
        mutateAndProject(recovery = true, operation = { restoreFrom(it, sessionId) }) { store, snapshot ->
            LocalOverview(snapshot, journalFrom(store), savedAdventuresFrom(store), feedbackFrom(store))
        }

    private fun restoreFrom(store: RecordTransaction, sessionId: String?): StoreChange<Snapshot?> {
        var changed = migrateLegacySelection(store)
        val id = sessionId ?: store.root.stringOrNull("last_session_id") ?: return StoreChange(null, changed)
        val record = read(store, id)
        if (!record.optionalObject("metadata")?.stringOrNull("superseded_by").isNullOrEmpty()) {
            throw ApiException(409, "SESSION_SUPERSEDED")
        }
        val state = record.getJSONObject("state")
        val rules = library.rulesFor(state.getJSONObject("game"))
        if (state.getString("status") == "ACTIVE") {
            pause(state, "USER")
            persist(state)
            store.putSession(id, record)
            changed = true
        }
        if (store.root.opt("last_session_id") != id) {
            store.putRoot(store.root.put("last_session_id", id))
            changed = true
        }
        return StoreChange(library.decorate(Snapshot.from(publicState(state, rules))), changed)
    }

    /** Dates remain unknown for 0.2 sessions; never invent a historical completion time. */
    suspend fun journal(): List<FamilyJournalEntry> = transaction { journalFrom(it) }

    private fun journalFrom(store: RecordTransaction): List<FamilyJournalEntry> =
        store.sessionIds().mapNotNull { id ->
            val record = read(store, id)
            val state = record.getJSONObject("state")
            val game = state.getJSONObject("game")
            if (state.getString("status") != "ENDED" || !game.getBoolean("finished")) return@mapNotNull null
            val metadata = record.optionalObject("metadata") ?: JSONObject()
            val card = library.card(game.getString("pack_id"), game.getString("pack_version"))
            val details = library.details(game)
            val scores = game.getJSONObject("score_by_player")
            FamilyJournalEntry(id, card.packId, card.version, metadata.optString("title", card.title),
                metadata.optString("art_key", card.artKey), metadata.optionalTime("created_at_ms"),
                metadata.optionalTime("completed_at_ms"), participants(game), game.getJSONArray("history").length(),
                game.getString("ending"), metadata.optString("ending_title", details.getString("ending_title")),
                metadata.optString("ending_summary", details.getString("ending_summary")),
                scores.keyList().sumOf { scores.getInt(it) }, library.choices(game),
                game.getJSONArray("players").objects().map { player ->
                    JournalParticipant(metadata.optionalObject("participant_profile_ids")?.stringOrNull(player.getString("player_id")),
                        player.getString("nickname"))
                })
        }.sortedWith(compareByDescending<FamilyJournalEntry> { it.completedAtEpochMillis ?: Long.MIN_VALUE }
            .thenBy { it.sessionId })

    /** Only explicit feedback from a known participant in a completed local adventure. */
    suspend fun setFeedback(sessionId: String, childId: String, feedback: AdventureFeedback?) = mutation { store ->
        val record = read(store, sessionId)
        val state = record.getJSONObject("state")
        if (state.getString("status") != "ENDED" || !state.getJSONObject("game").getBoolean("finished")) {
            throw ApiException(409, "FEEDBACK_NOT_READY")
        }
        val metadata = record.optionalObject("metadata") ?: throw ApiException(409, "PARTICIPANT_UNKNOWN")
        val identities = metadata.optionalObject("participant_profile_ids") ?: throw ApiException(409, "PARTICIPANT_UNKNOWN")
        if (identities.keyList().none { identities.getString(it) == childId }) throw ApiException(409, "PARTICIPANT_UNKNOWN")
        val existingEntries = metadata.optionalObject("feedback")
        var changed = existingEntries == null
        val entries = existingEntries ?: JSONObject().also { metadata.put("feedback", it) }
        for (id in entries.keyList()) {
            val existing = entries.getJSONObject(id)
            if (AdventureFeedback.entries.none { it.name == existing.getString("choice") })
                throw ApiException(409, "LOCAL_STORAGE_VERSION_UNAVAILABLE")
            existing.getLong("recorded_at_ms")
        }
        if (feedback == null) {
            if (entries.has(childId)) {
                entries.remove(childId)
                changed = true
            }
        }
        else if (entries.optJSONObject(childId)?.optString("choice") != feedback.name) {
            entries.put(childId, JSONObject().put("choice", feedback.name).put("recorded_at_ms", nowMillis()))
            changed = true
        }
        if (changed) store.putSession(sessionId, record)
        StoreChange(Unit, changed)
    }

    suspend fun feedback(): List<AdventureFeedbackEntry> = transaction { feedbackFrom(it) }

    private fun feedbackFrom(store: RecordTransaction): List<AdventureFeedbackEntry> =
        store.sessionIds().flatMap { id ->
            val record = read(store, id)
            val state = record.getJSONObject("state")
            val game = state.getJSONObject("game")
            if (state.getString("status") != "ENDED" || !game.getBoolean("finished")) return@flatMap emptyList()
            val metadata = record.optionalObject("metadata") ?: return@flatMap emptyList()
            val identities = metadata.optionalObject("participant_profile_ids") ?: return@flatMap emptyList()
            val participants = identities.keyList().map { identities.getString(it) }.toSet()
            val entries = metadata.optionalObject("feedback") ?: return@flatMap emptyList()
            entries.keyList().filter { it in participants }.map { child ->
                val entry = entries.getJSONObject(child)
                AdventureFeedbackEntry(id, game.getString("pack_id"), child,
                    (AdventureFeedback.entries.firstOrNull { it.name == entry.getString("choice") }
                        ?: throw ApiException(409, "LOCAL_STORAGE_VERSION_UNAVAILABLE")), entry.getLong("recorded_at_ms"))
            }
        }.sortedWith(compareByDescending<AdventureFeedbackEntry> { it.recordedAtEpochMillis }
            .thenBy { it.sessionId }.thenBy { it.childId })

    /** One most recently selected/created unfinished session per pack; older records stay durable. */
    suspend fun savedAdventures(): List<SavedAdventure> = transaction { savedAdventuresFrom(it) }

    private fun savedAdventuresFrom(store: RecordTransaction): List<SavedAdventure> {
        val latest = store.root.stringOrNull("last_session_id")
        val replacedLegacy = legacyReplacedSessions(store).second.toSet()
        return store.sessionIds().map { id -> id to read(store, id) }
            .filter { (id, record) -> id !in replacedLegacy && record.getJSONObject("state").getString("status") != "ENDED" &&
                record.optionalObject("metadata")?.stringOrNull("superseded_by").isNullOrEmpty() }
            .sortedWith(compareByDescending<Pair<String, JSONObject>> { it.first == latest }
                .thenByDescending { it.second.optionalObject("metadata")?.optionalTime("created_at_ms") ?: Long.MIN_VALUE }
                .thenBy { it.first })
            .distinctBy { it.second.getJSONObject("state").getJSONObject("game").getString("pack_id") }
            .map { (id, record) ->
                val game = record.getJSONObject("state").getJSONObject("game")
                val card = library.card(game.getString("pack_id"), game.getString("pack_version"))
                val metadata = record.optionalObject("metadata") ?: JSONObject()
                SavedAdventure(id, card.packId, metadata.optString("title", card.title),
                    metadata.optString("art_key", card.artKey), metadata.optionalTime("updated_at_ms"),
                    game.getJSONArray("history").length(), card.totalChoices, participants(game))
            }
    }

    /** Explicit deletion covers every active, previous and partially migrated game store. */
    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        val file = File(application.noBackupFilesDir, "$storageName.json")
        locks.getOrPut(file.absolutePath) { Mutex() }.withLock {
            // Deletion does not need the old schema, content packs or a working decryption key.
            recordStore.deleteAll()
        }
    }

    override suspend fun command(id: String, body: JSONObject): JSONObject = mutation(
        recovery = body.optString("type") in setOf("session.pause", "session.end"),
    ) { store ->
        val command = validateCommand(body)
        val record = read(store, id)
        val state = record.getJSONObject("state")
        val commandId = command.getString("command_id")
        val hash = sha256(canonicalJson(command))
        lookup(store, id, commandId, hash)?.let { return@mutation StoreChange(it, changed = false) }
        // A receipt retry stays a read-only success, even at the storage capacity boundary.
        migrateLegacySelection(store)
        if (!record.optionalObject("metadata")?.stringOrNull("superseded_by").isNullOrEmpty() &&
            command.getString("type") !in setOf("session.pause", "session.end")) {
            throw ApiException(409, "SESSION_SUPERSEDED")
        }
        val rules = library.rulesFor(state.getJSONObject("game"))
        if (command.getLong("expected_version") != state.getLong("version")) throw ApiException(409, "STALE_VERSION")
        if (command.getString("turn_id") != state.getString("turn_id") || command.getLong("turn_epoch") != state.getLong("turn_epoch")) {
            throw ApiException(409, "STALE_TURN")
        }
        if (state.getString("status") == "ENDED") throw ApiException(409, "SESSION_ENDED")
        val payload = command.getJSONObject("payload")
        when (command.getString("type")) {
            "session.pause" -> pause(state, payload.getString("reason"))
            "session.end" -> end(state)
            "session.resume" -> {
                if (state.getString("status") != "PAUSED") throw ApiException(409, "NOT_PAUSED")
                state.put("status", "ACTIVE").put("pause_reason", JSONObject.NULL).put("requires_explicit_resume", false)
                    .put("turn_epoch", state.getLong("turn_epoch") + 1).put("turn_id", uid()).put("_captures", JSONArray())
                    .put("_clarifications", 0).put("_recovery_requests", 0)
                val unheard = state.optJSONArray("_unheard_texts")?.strings()
                if (!unheard.isNullOrEmpty()) {
                    val texts = unheard.toMutableList()
                    val game = state.getJSONObject("game")
                    if (!game.getBoolean("finished")) {
                        // An interrupted WAIT retains only its short reassurance.
                        // Reorient before listening, including already saved pauses.
                        val prompt = rules.present(game).getString("prompt")
                        if (texts.last() != prompt) texts.add(prompt)
                    }
                    planTexts(state, texts)
                } else plan(state, rules, prefix = "")
            }
            else -> {
                if (state.getString("status") != "ACTIVE") throw ApiException(409, "SESSION_PAUSED")
                when (command.getString("type")) {
                    "playback.completed" -> {
                        val speech = state.optJSONObject("speech_plan")
                        if (state.getString("phase") !in setOf("PROMPTING", "FEEDBACK") || speech == null ||
                            speech.getString("playback_id") != payload.getString("playback_id")) throw ApiException(409, "STALE_PLAYBACK")
                        state.put("playback_checkpoint", JSONObject().put("playback_id", speech.getString("playback_id"))
                            .put("segment_ids", JSONArray(speech.getJSONArray("segments").objects().map { it.getString("segment_id") })))
                            .put("speech_plan", JSONObject.NULL)
                        state.remove("_unheard_texts")
                        if (state.getJSONObject("game").getBoolean("finished")) {
                            state.put("status", "ENDED").put("phase", "FEEDBACK").put("listen_window_id", JSONObject.NULL)
                        } else state.put("phase", "LISTENING").put("listen_window_id", uid())
                    }
                    "action.submit" -> {
                        if (state.getString("phase") != "LISTENING" || payload.getString("listen_window_id") != state.stringOrNull("listen_window_id")) {
                            throw ApiException(409, "INPUT_NOT_READY")
                        }
                        val captures = state.getJSONArray("_captures").strings()
                        if (payload.getString("capture_id") in captures) throw ApiException(409, "CAPTURE_REUSED")
                        state.put("_captures", JSONArray((captures + payload.getString("capture_id")).takeLast(64)))
                        // Text exists only in this invocation. No resolver, network, transcript log or persistence.
                        applyCandidate(state, rules, rules.interpret(payload.getString("text"), state.getJSONObject("game")))
                    }
                }
            }
        }
        persist(state)
        val metadata = record.optionalObject("metadata") ?: JSONObject().also { record.put("metadata", it) }
        metadata.put("updated_at_ms", nowMillis())
        if (state.getString("status") == "ENDED" && state.getJSONObject("game").getBoolean("finished") &&
            metadata.isNull("completed_at_ms")) {
            val details = library.details(state.getJSONObject("game"))
            metadata.put("completed_at_ms", nowMillis()).put("ending_title", details.getString("ending_title"))
                .put("ending_summary", details.getString("ending_summary"))
        }
        val response = JSONObject().put("command_id", commandId).put("command_status", "APPLIED")
            .put("snapshot", publicState(state, rules))
        store.putSession(id, record)
        store.putReceipt(id, commandId, JSONObject().put("body_hash", hash).put("result", response))
        StoreChange(response.copyJson(), changed = true)
    }

    private fun applyCandidate(state: JSONObject, rules: LocalGameEngine, input: LocalCandidate) {
        var candidate = input
        val game = state.getJSONObject("game")
        when (candidate.intent) {
            "PAUSE" -> { pause(state, "QUIET_REQUEST"); return }
            "END" -> { end(state); return }
            "HINT", "REPEAT", "WAIT" -> {
                // Wait/repeat retain an understood choice; they never guess its owner.
                // Help still returns to the complete choice question.
                if (candidate.intent == "HINT") state.remove("_pending_action")
                val requests = state.optInt("_recovery_requests", 0) + 1
                state.put("_recovery_requests", requests)
                // Help/repeat/wait share a finite budget and never reset errors.
                if (requests > 2) { pauseForRecovery(state); return }
                when (candidate.intent) {
                    "HINT" -> {
                        val hint = try {
                            state.put("game", rules.applyHint(game))
                            rules.present(state.getJSONObject("game")).optString("hint").ifEmpty { "선택지를 천천히 살펴보자." }
                        } catch (_: LocalGameError) { "사용할 힌트가 남아 있지 않아. 선택지를 다시 들려줄게." }
                        plan(state, rules, hint)
                    }
                    "WAIT" -> plan(state, rules,
                        if (state.stringOrNull("_pending_action") != null)
                            "천천히 생각해도 괜찮아. 누구의 선택인지 이름만 말해 줄래?"
                        else "천천히 생각해도 괜찮아.", includePrompt = false)
                    else -> if (state.stringOrNull("_pending_action") != null) {
                        // The current question asks only for an owner, not another choice.
                        plan(state, rules, "무엇을 할지는 들었어. 누구의 선택인지 이름만 말해 줄래?", includePrompt = false)
                    } else {
                        // Repeat the complete current question, not the previous choice's feedback.
                        plan(state, rules, prefix = "")
                    }
                }
                return
            }
        }
        if (candidate.reason == "missing_action" && candidate.actionId == null && candidate.declaredPlayerId != null && !state.isNull("_pending_action")) {
            candidate = candidate.copy(intent = "ACTION", actionId = state.getString("_pending_action"), needsClarification = false)
        }
        val actor = candidate.declaredPlayerId ?: if (game.getJSONArray("players").length() == 1) game.getJSONArray("players").getJSONObject(0).getString("player_id") else null
        if (actor != null && actor != game.stringOrNull("expected_player_id")) {
            clarify(state, rules, candidate.copy(reason = "wrong_player")); return
        }
        if (candidate.intent != "ACTION" || candidate.needsClarification || actor == null || candidate.actionId == null) {
            clarify(state, rules, candidate); return
        }
        val next = try { rules.applyAction(game, actor, candidate.actionId) } catch (_: LocalGameError) {
            clarify(state, rules, LocalCandidate(reason = "not_allowed")); return
        }
        state.put("game", next).put("_scene_feedback", next.optString("feedback"))
            .put("turn_id", uid()).put("_captures", JSONArray()).put("_clarifications", 0).put("_recovery_requests", 0)
        state.remove("_pending_action")
        plan(state, rules, next.optString("feedback"))
    }

    private fun clarify(state: JSONObject, rules: LocalGameEngine, candidate: LocalCandidate) {
        val count = state.getInt("_clarifications") + 1
        state.put("_clarifications", count)
        if (count >= 2) { pauseForRecovery(state); return }
        val game = state.getJSONObject("game")
        val allowed = rules.present(game).getJSONArray("actions").objects().map { it.getString("id") }
        if (candidate.actionId != null && candidate.actionId in allowed && candidate.declaredPlayerId == null && count == 1) {
            state.put("_pending_action", candidate.actionId)
            plan(state, rules, "무엇을 할지는 들었어. 누구의 선택인지 이름만 말해 줄래?", includePrompt = false)
            return
        }
        state.remove("_pending_action")
        val prefix = if (candidate.reason == "address_only") "응, 여기 있어."
        else if (candidate.reason == "repair_requested") "아직 선택하지 않았어. 원하는 선택을 다시 말해 줘."
        else if (candidate.reason == "unconfirmed_expansion") "그 말은 내가 잘 이해하지 못했어. 다시 물어볼게."
        else if (candidate.reason == "wrong_player") {
            val name = game.getJSONArray("players").objects().first { it.getString("player_id") == game.getString("expected_player_id") }.getString("nickname")
            "지금은 $name 차례야. 같은 기회로 다시 해 보자."
        } else "어느 쪽인지 내가 잘 모르겠어. 다시 물어볼게."
        plan(state, rules, prefix)
    }

    private fun pauseForRecovery(state: JSONObject) {
        state.remove("_pending_action")
        state.remove("_unheard_texts")
        pause(state, "NO_RESPONSE")
    }

    private fun plan(state: JSONObject, rules: LocalGameEngine, prefix: String? = null, includePrompt: Boolean = true) {
        val actualPrefix = prefix ?: state.stringOrNull("_scene_feedback")
        val prompt = if (includePrompt) rules.present(state.getJSONObject("game")).getString("prompt") else null
        planTexts(state, listOfNotNull(actualPrefix, prompt).filter { it.isNotEmpty() })
    }

    private fun planTexts(state: JSONObject, texts: List<String>) {
        state.put("_unheard_texts", JSONArray(texts))
        state.put("speech_plan", JSONObject().put("playback_id", uid()).put("turn_id", state.getString("turn_id"))
            .put("turn_epoch", state.getLong("turn_epoch")).put("segments", JSONArray(texts.map {
                JSONObject().put("segment_id", uid()).put("text", it).put("required", true)
            })))
            .put("listen_window_id", JSONObject.NULL)
            .put("phase", if (state.getJSONObject("game").getBoolean("finished")) "FEEDBACK" else "PROMPTING")
    }

    private fun pause(state: JSONObject, reason: String) {
        if (!state.isNull("_pending_action") && !state.isNull("_unheard_texts")) {
            // Replay full choices on resume, not a name-only confirmation for a
            // tentative action that quiet mode is about to discard.
            val game = state.getJSONObject("game")
            val prompt = library.rulesFor(game).present(game).getString("prompt")
            state.put("_unheard_texts", JSONArray().put(prompt))
        }
        state.remove("_pending_action")
        state.put("status", "PAUSED").put("phase", "PROMPTING").put("turn_epoch", state.getLong("turn_epoch") + 1)
            .put("speech_plan", JSONObject.NULL).put("listen_window_id", JSONObject.NULL).put("requires_explicit_resume", true)
        if (state.stringOrNull("pause_reason") !in setOf("QUIET_REQUEST", "NO_RESPONSE")) state.put("pause_reason", reason)
    }

    private fun end(state: JSONObject) {
        state.put("status", "ENDED").put("speech_plan", JSONObject.NULL).put("listen_window_id", JSONObject.NULL)
            .put("requires_explicit_resume", true).put("turn_epoch", state.getLong("turn_epoch") + 1)
    }

    private fun persist(state: JSONObject) {
        state.put("version", state.getLong("version") + 1).put("last_seq", state.getLong("last_seq") + 1)
    }

    private fun publicState(state: JSONObject, rules: LocalGameEngine): JSONObject {
        val result = JSONObject()
        state.keyList().filterNot { it.startsWith("_") }.forEach { result.put(it, state.get(it)) }
        result.put("presentation", rules.present(state.getJSONObject("game")).put("input_kind",
            if (state.getString("status") == "ACTIVE" && state.stringOrNull("_pending_action") != null) "player_name" else "choice"))
        return result.copyJson()
    }

    private fun read(store: RecordTransaction, id: String): JSONObject =
        store.readSession(id) ?: throw ApiException(404, "SESSION_NOT_FOUND")

    /** 0.2 restored only last_session_id and bundled only the dinosaur pack. */
    private fun legacyReplacedSessions(store: RecordTransaction): Pair<String?, List<String>> {
        val selectedId = store.root.stringOrNull("last_session_id") ?: return null to emptyList()
        val selected = store.readSession(selectedId) ?: return null to emptyList()
        val metadata = selected.optionalObject("metadata")
        val selectedGame = selected.getJSONObject("state").getJSONObject("game")
        if (metadata?.optionalTime("created_at_ms") != null ||
            !metadata?.stringOrNull("superseded_by").isNullOrEmpty() ||
            selectedGame.getString("pack_id") != DEFAULT_ADVENTURE_ID) return null to emptyList()
        val replaced = store.sessionIds().filter { id ->
            val record = store.readSession(id) ?: throw JSONException("Invalid stored session")
            val state = record.getJSONObject("state")
            id != selectedId && record.optionalObject("metadata")?.optionalTime("created_at_ms") == null &&
                state.getString("status") != "ENDED" &&
                state.getJSONObject("game").getString("pack_id") == DEFAULT_ADVENTURE_ID
        }
        return selectedId to replaced
    }

    /** Add only replacement metadata in the same atomic commit; keep dates/state/receipts intact. */
    private fun migrateLegacySelection(store: RecordTransaction): Boolean {
        val (selectedId, replaced) = legacyReplacedSessions(store)
        if (selectedId == null) return false
        var changed = false
        for (id in replaced) {
            val record = read(store, id)
            val metadata = record.optionalObject("metadata") ?: JSONObject().also { record.put("metadata", it) }
            if (metadata.opt("superseded_by") != selectedId) {
                metadata.put("superseded_by", selectedId)
                store.putSession(id, record)
                changed = true
            }
        }
        return changed
    }

    private fun lookup(store: RecordTransaction, sessionId: String, id: String, hash: String? = null): JSONObject? {
        val receipt = store.readReceipt(sessionId, id) ?: return null
        if (hash != null && receipt.getString("body_hash") != hash) throw ApiException(409, "COMMAND_ID_REUSED")
        return receipt.getJSONObject("result").copyJson()
    }

    private suspend fun <T> withLibrary(operation: (LocalAdventureLibrary) -> T): T = withContext(Dispatchers.IO) {
        val file = File(application.noBackupFilesDir, "$storageName.json")
        locks.getOrPut(file.absolutePath) { Mutex() }.withLock { operation(library) }
    }

    private data class StoreChange<T>(val value: T, val changed: Boolean)

    private suspend fun <T> transaction(operation: (RecordTransaction) -> T): T =
        mutation { StoreChange(operation(it), changed = false) }

    private suspend fun <T> mutation(recovery: Boolean = false, operation: (RecordTransaction) -> StoreChange<T>): T =
        mutateAndProject(recovery, operation) { _, result -> result }

    private fun <T> storedOperation(operation: () -> T): T = try {
        operation()
    } catch (error: LocalGameError) {
        if (error.code == "content_version_mismatch") throw ApiException(409, "CONTENT_VERSION_UNAVAILABLE")
        throw ApiException(422, "INVALID_GAME_INPUT")
    } catch (_: JSONException) {
        // A damaged record must not escape as a crash or be silently discarded.
        throw ApiException(500, "LOCAL_STORAGE_UNAVAILABLE")
    }

    private suspend fun <T, R> mutateAndProject(
        recovery: Boolean,
        operation: (RecordTransaction) -> StoreChange<T>,
        project: (RecordTransaction, T) -> R,
    ): R = withContext(Dispatchers.IO) {
        val file = File(application.noBackupFilesDir, "$storageName.json")
        locks.getOrPut(file.absolutePath) { Mutex() }.withLock {
            storedOperation {
                recordStore.open().use { store ->
                    val result = operation(store)
                    if (result.changed) store.commit(recovery)
                    // A completed commit remains durable if a later list projection fails.
                    // Keep the same store mutex until every overview field has been read.
                    project(store, result.value)
                }
            }
        }
    }

    private fun JSONObject.optionalObject(key: String): JSONObject? =
        if (!has(key) || isNull(key)) null else optJSONObject(key) ?: throw JSONException("Invalid stored object")

    /** Validate every legacy record before promoting a plaintext archive to a private envelope. */
    private fun validateLegacyStore(store: JSONObject) {
        val sessions = store.getJSONObject("sessions")
        val selected = store.opt("last_session_id")
        if (selected != null && selected != JSONObject.NULL && (selected !is String || !sessions.has(selected)))
            throw JSONException("Invalid selected session")
        for (id in sessions.keyList()) {
            val record = sessions.getJSONObject(id)
            val state = record.getJSONObject("state")
            if (state.opt("session_id") != id) throw JSONException("Invalid session identity")
            if (state.opt("status") !in setOf("ACTIVE", "PAUSED", "ENDED"))
                throw ApiException(409, "LOCAL_STORAGE_VERSION_UNAVAILABLE")
            val game = state.getJSONObject("game")
            Snapshot.from(publicState(state, library.rulesFor(game)))
            game.getJSONArray("history")
            game.getJSONObject("score_by_player")
            participants(game)
            state.getJSONArray("_captures")
            state.getInt("_clarifications")
            val receipts = record.getJSONObject("receipts")
            for (command in receipts.keyList()) {
                val receipt = receipts.getJSONObject(command)
                if (!Regex("[a-f0-9]{64}").matches(receipt.getString("body_hash")))
                    throw JSONException("Invalid receipt hash")
                val result = receipt.getJSONObject("result")
                if (result.opt("command_id") != command) throw JSONException("Invalid receipt identity")
                Snapshot.from(result)
            }
            record.optionalObject("metadata")?.let { metadata ->
                metadata.optionalObject("participant_profile_ids")?.let { identities ->
                    if (identities.keyList().any { identities.opt(it) !is String })
                        throw JSONException("Invalid participant identity")
                }
                metadata.optionalObject("feedback")?.let { feedback ->
                    for (child in feedback.keyList()) {
                        val entry = feedback.getJSONObject(child)
                        if (AdventureFeedback.entries.none { it.name == entry.getString("choice") })
                            throw ApiException(409, "LOCAL_STORAGE_VERSION_UNAVAILABLE")
                        if (entry.opt("recorded_at_ms") !is Number) throw JSONException("Invalid feedback time")
                    }
                }
            }
        }
    }

    private fun participants(game: JSONObject) = game.getJSONArray("players").objects().map { it.getString("nickname") }
    private fun JSONObject.optionalTime(key: String): Long? = (opt(key) as? Number)?.toLong()

    companion object {
        internal const val NORMAL_STORE_BYTES = 15 * 1024 * 1024
        internal const val MAX_STORE_BYTES = 16 * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun uid() = UUID.randomUUID().toString()
        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun invalid(): Nothing = throw ApiException(422, "INVALID_REQUEST")
        private fun exactKeys(obj: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
            if (!obj.keyList().containsAll(required) || obj.keyList().any { it !in required && it !in optional }) invalid()
        }
        private fun string(obj: JSONObject, key: String, max: Int): String {
            val value = obj.opt(key) as? String ?: invalid()
            if (value.codePointCount(0, value.length) !in 1..max) invalid()
            return value
        }
        private fun identifier(obj: JSONObject, key: String, max: Int = 96): String = string(obj, key, max).also {
            if (!Regex("[A-Za-z0-9_-]+").matches(it)) invalid()
        }
        private fun positiveInteger(obj: JSONObject, key: String) {
            val value = obj.opt(key)
            if ((value !is Int && value !is Long) || (value as Number).toLong() < 1L) invalid()
        }

        private fun validatePlayers(body: JSONObject): JSONArray {
            exactKeys(body, setOf("players"))
            val players = body.optJSONArray("players") ?: invalid()
            if (players.length() !in 1..2) invalid()
            val result = JSONArray()
            val ids = mutableSetOf<String>()
            val names = mutableSetOf<String>()
            for (index in 0 until players.length()) {
                val player = players.optJSONObject(index) ?: invalid()
                exactKeys(player, setOf("player_id", "nickname"))
                val id = identifier(player, "player_id", 64)
                if (!ids.add(id)) invalid()
                val nickname = pythonWords(Normalizer.normalize(string(player, "nickname", 12), Normalizer.Form.NFC)).joinToString(" ")
                if (nickname.isEmpty() || Regex("\\p{C}").containsMatchIn(nickname)) invalid()
                if (!names.add(UCharacter.foldCase(nickname, true))) invalid()
                result.put(JSONObject().put("player_id", id).put("nickname", nickname))
            }
            return result
        }

        private fun validateCommand(body: JSONObject): JSONObject {
            exactKeys(body, setOf("command_id", "expected_version", "turn_id", "turn_epoch", "type", "payload"))
            identifier(body, "command_id"); identifier(body, "turn_id")
            positiveInteger(body, "expected_version"); positiveInteger(body, "turn_epoch")
            val type = string(body, "type", 32)
            val payload = body.optJSONObject("payload")?.copyJson() ?: invalid()
            when (type) {
                "action.submit" -> {
                    exactKeys(payload, setOf("text", "is_final", "capture_id", "listen_window_id"), setOf("locale", "input_source"))
                    string(payload, "text", 512); identifier(payload, "capture_id"); identifier(payload, "listen_window_id")
                    if (payload.opt("is_final") != true) invalid()
                    if (!payload.has("locale")) payload.put("locale", "ko-KR")
                    if (!payload.has("input_source")) payload.put("input_source", "mock")
                    if (payload.opt("locale") != "ko-KR" || payload.opt("input_source") !in setOf("mock", "pleos_stt", "android_on_device", "touch")) invalid()
                }
                "playback.completed" -> { exactKeys(payload, setOf("playback_id")); identifier(payload, "playback_id") }
                "session.pause" -> {
                    exactKeys(payload, emptySet(), setOf("reason"))
                    if (!payload.has("reason")) payload.put("reason", "QUIET_REQUEST")
                    if (payload.opt("reason") !in setOf("USER", "QUIET_REQUEST", "NO_RESPONSE", "AUDIO_FOCUS", "NETWORK", "MIC_PERMISSION", "VEHICLE_POLICY", "SUSPEND")) invalid()
                }
                "session.resume", "session.end" -> exactKeys(payload, emptySet())
                else -> invalid()
            }
            return body.copyJson().put("payload", payload)
        }
    }
}

/** Stable JSON for hashes and semantic parity assertions; object ordering is not significant. */
internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keyList().sorted().joinToString(",", "{", "}") { jsonString(it) + ":" + canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    is String -> jsonString(value)
    is Boolean, is Number -> value.toString()
    else -> error("Unsupported JSON value")
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (char in value) when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
    }
    append('"')
}
