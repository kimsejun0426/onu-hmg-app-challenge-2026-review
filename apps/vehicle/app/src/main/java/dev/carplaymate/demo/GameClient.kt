package dev.carplaymate.demo

import org.json.JSONObject

/** The same command contract is used by the bundled game and the optional development API. */
interface GameClient {
    suspend fun create(players: JSONObject): Snapshot
    /** Older HTTP backends retain their original content contract; never silently substitute a pack. */
    suspend fun createAdventure(players: JSONObject, packId: String): Snapshot {
        if (packId != DEFAULT_ADVENTURE_ID) throw ApiException(409, "ADVENTURE_NOT_SUPPORTED")
        return create(players)
    }
    suspend fun snapshot(id: String): Snapshot
    suspend fun command(id: String, body: JSONObject): JSONObject
    suspend fun receipt(session: String, command: String): JSONObject
}
