package dev.carplaymate.demo

import android.content.Context
import org.json.JSONObject

const val DEFAULT_ADVENTURE_ID = "dinosaur-lab-missing-egg"

data class AdventureCard(
    val packId: String,
    val version: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val artKey: String,
    val estimatedMinutes: Int,
    val durationLabel: String,
    val totalChoices: Int,
    val hintScorePenalty: Int = 0,
    val interestTags: Set<String> = emptySet(),
    val playStyle: String = "explore",
)

data class FamilyJournalEntry(
    val sessionId: String,
    val packId: String,
    val packVersion: String,
    val title: String,
    val artKey: String,
    val createdAtEpochMillis: Long?,
    val completedAtEpochMillis: Long?,
    val participants: List<String>,
    val choicesCount: Int,
    val endingId: String,
    val endingTitle: String,
    val summary: String,
    val totalScore: Int,
    val choices: List<JournalChoice> = emptyList(),
    val participantProfiles: List<JournalParticipant> = emptyList(),
)

data class JournalChoice(
    val sceneId: String,
    val sceneTitle: String,
    val actionId: String,
    val actionLabel: String,
    val participant: String,
    val usedTool: Boolean,
)

data class SavedAdventure(
    val sessionId: String,
    val packId: String,
    val title: String,
    val artKey: String,
    val updatedAtEpochMillis: Long?,
    val choicesCount: Int,
    val totalChoices: Int,
    val participants: List<String>,
)

/** Asset access is called only from LocalGameClient's IO dispatcher. */
internal class LocalAdventureLibrary(private val context: Context) {
    private data class Catalog(
        val current: Map<String, JSONObject>,
        val versions: Map<Pair<String, String>, JSONObject>,
    )

    private val catalog: Catalog by lazy {
        val manifest = read("content/catalog.json")
        require(manifest.getInt("schema_version") == 1)
        val packs = manifest.getJSONArray("packs").objects()
        require(packs.isNotEmpty() && packs.map { it.getString("pack_id") }.distinct().size == packs.size)
        require(manifest.getString("default_pack_id") == DEFAULT_ADVENTURE_ID)
        require(packs.any { it.getString("pack_id") == DEFAULT_ADVENTURE_ID })
        val all = packs + (manifest.optJSONArray("legacy_packs")?.objects() ?: emptyList())
        val versions = all.associateBy { it.getString("pack_id") to it.getString("version") }
        require(versions.size == all.size) { "Duplicate content version" }
        Catalog(packs.associateBy { it.getString("pack_id") }, versions)
    }
    private val engines = mutableMapOf<String, LocalGameEngine>()
    private val content = mutableMapOf<String, JSONObject>()

    fun cards(): List<AdventureCard> = catalog.current.values.map(::cardFrom)

    // New games use the current edition; saved choices must keep their original rules and words.
    private fun entryFor(packId: String, version: String?): JSONObject =
        (if (version == null) catalog.current[packId] else catalog.versions[packId to version])
            ?: throw ApiException(409, "CONTENT_VERSION_UNAVAILABLE")

    fun card(packId: String, version: String? = null): AdventureCard = cardFrom(entryFor(packId, version))

    fun rules(packId: String, version: String? = null): LocalGameEngine {
        val card = card(packId, version)
        return engines.getOrPut("${card.packId}@${card.version}") {
            LocalGameEngine(contentFor(entryFor(card.packId, card.version)))
        }
    }

    fun rulesFor(game: JSONObject): LocalGameEngine = rules(game.getString("pack_id"), game.getString("pack_version"))

    /** Presentation additions never enter the command receipts or change original parity fixtures. */
    fun decorate(snapshot: Snapshot): Snapshot {
        val game = snapshot.game
        val card = card(game.getString("pack_id"), game.getString("pack_version"))
        val details = details(game)
        val presentation = snapshot.presentation.copyJson()
            .put("pack_title", card.title).put("total_choices", card.totalChoices)
            .put("hint_score_penalty", card.hintScorePenalty)
        details.keyList().forEach { presentation.put(it, details.get(it)) }
        return snapshot.copy(presentation = presentation)
    }

    fun details(game: JSONObject): JSONObject {
        val card = card(game.getString("pack_id"), game.getString("pack_version"))
        val engine = rulesFor(game)
        val scene = engine.sceneMetadata(game)
        val ending = game.stringOrNull("ending")?.let {
            entryFor(card.packId, card.version).optJSONObject("endings")?.optJSONObject(it)
        }
        fun rendered(value: String) = engine.renderMetadata(value, game)
        val finished = game.optBoolean("finished")
        return JSONObject().put("scene_title", rendered(scene.optString("title", card.title)))
            .put("art_key", scene.optString("art_key", card.artKey))
            .put("ending_title", if (finished) rendered(scene.optString("ending_title",
                ending?.optString("title")?.takeIf { it.isNotBlank() } ?: "함께 마친 모험")) else "")
            .put("ending_summary", if (finished) rendered(scene.optString("summary",
                ending?.optString("summary")?.takeIf { it.isNotBlank() } ?: engine.present(game).getString("prompt"))) else "")
    }

    fun choices(game: JSONObject): List<JournalChoice> {
        val engine = rulesFor(game)
        val players = game.getJSONArray("players").objects().associate {
            it.getString("player_id") to it.getString("nickname")
        }
        return game.getJSONArray("history").objects().map { item ->
            val sceneId = item.getString("scene_id")
            val actionId = item.getString("action_id")
            val detail = engine.actionMetadata(sceneId, actionId)
            val costs = item.getJSONObject("resource_cost")
            JournalChoice(sceneId, detail.getString("scene_title"), actionId, detail.getString("action_label"),
                players.getValue(item.getString("player_id")), costs.keyList().any { costs.getInt(it) > 0 })
        }
    }

    private fun cardFrom(entry: JSONObject) = AdventureCard(
        entry.getString("pack_id"), entry.getString("version"), entry.getString("title"),
        entry.getString("subtitle"), entry.getString("description"), entry.getString("art_key"),
        entry.getInt("estimated_minutes"), entry.getString("duration_label"), entry.getInt("total_choices"),
        contentFor(entry).optInt("hint_score_penalty", 0),
        entry.getJSONArray("interest_tags").strings().toSet(), entry.getString("play_style"),
    ).also { require(it.estimatedMinutes > 0 && it.totalChoices > 0 && it.title.isNotBlank() &&
        it.interestTags.isNotEmpty() && InterestOptions.ids.containsAll(it.interestTags) &&
        it.playStyle in setOf("explore", "observe", "imagine", "solve")) }

    private fun contentFor(entry: JSONObject): JSONObject = content.getOrPut(
        "${entry.getString("pack_id")}@${entry.getString("version")}",
    ) {
        val path = entry.getString("content_asset")
        require(path.startsWith("content/") && path.endsWith("/episode.json") && ".." !in path)
        read(path).also { pack ->
            require(pack.getString("pack_id") == entry.getString("pack_id") &&
                pack.getString("version") == entry.getString("version"))
        }
    }

    private fun read(path: String): JSONObject = JSONObject(context.assets.open(path)
        .bufferedReader(Charsets.UTF_8).use { it.readText() })
}
