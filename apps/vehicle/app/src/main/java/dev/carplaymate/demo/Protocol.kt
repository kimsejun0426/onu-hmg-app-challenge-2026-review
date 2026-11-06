package dev.carplaymate.demo

import org.json.JSONArray
import org.json.JSONObject

data class SpeechSegment(val segmentId: String, val text: String, val required: Boolean)
data class SpeechPlan(
    val playbackId: String,
    val turnId: String,
    val turnEpoch: Long,
    val segments: List<SpeechSegment>,
)
data class Snapshot(
    val sessionId: String,
    val version: Long,
    val turnId: String,
    val turnEpoch: Long,
    val status: String,
    val phase: String,
    val listenWindowId: String?,
    val speechPlan: SpeechPlan?,
    val game: JSONObject,
    val presentation: JSONObject = JSONObject(),
) {
    companion object {
        fun from(json: JSONObject): Snapshot {
            val obj = json.optJSONObject("snapshot") ?: json
            val speech = obj.optJSONObject("speech_plan")?.let { plan ->
                val segments = plan.getJSONArray("segments")
                SpeechPlan(
                    plan.getString("playback_id"),
                    plan.getString("turn_id"),
                    plan.getLong("turn_epoch"),
                    List(segments.length()) { index ->
                        val s = segments.getJSONObject(index)
                        SpeechSegment(s.getString("segment_id"), s.getString("text"), s.optBoolean("required", true))
                    },
                )
            }
            return Snapshot(
                obj.getString("session_id"), obj.getLong("version"),
                obj.getString("turn_id"), obj.getLong("turn_epoch"),
                obj.getString("status"), obj.getString("phase"),
                obj.optString("listen_window_id").takeIf { it.isNotBlank() && it != "null" },
                speech, obj.getJSONObject("game"), obj.optJSONObject("presentation") ?: JSONObject(),
            )
        }
    }
}

data class FinalUtterance(val captureId: String, val text: String, val listenWindowId: String)

fun commandEnvelope(snapshot: Snapshot, id: String, type: String, payload: JSONObject): JSONObject =
    JSONObject().put("command_id", id).put("expected_version", snapshot.version)
        .put("turn_id", snapshot.turnId).put("turn_epoch", snapshot.turnEpoch)
        .put("type", type).put("payload", payload)

fun playersBody(first: String, second: String?): JSONObject {
    val players = JSONArray().put(JSONObject().put("player_id", "p1").put("nickname", first.trim()))
    if (!second.isNullOrBlank()) players.put(JSONObject().put("player_id", "p2").put("nickname", second.trim()))
    return JSONObject().put("players", players)
}
