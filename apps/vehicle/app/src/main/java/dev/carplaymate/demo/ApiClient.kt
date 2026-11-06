package dev.carplaymate.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApiException(val status: Int, val code: String) : Exception("HTTP $status / $code")

/** Development-only bearer token remains in memory. Never log headers or request bodies. */
class ApiClient(baseUrl: String, private val token: String) : GameClient {
    private val base = baseUrl.trim().trimEnd('/')
    init {
        val uri = URI(base)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty()) { "Use the server origin, without a path." }
        require(uri.scheme == "https" ||
            (uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost"))) {
            "HTTP is limited to the development emulator or loopback host."
        }
        require(token.isNotBlank()) { "A development token is required." }
    }

    override suspend fun create(players: JSONObject): Snapshot = Snapshot.from(request("POST", "/v1/sessions", players))
    override suspend fun snapshot(id: String): Snapshot = Snapshot.from(request("GET", "/v1/sessions/$id/snapshot"))
    override suspend fun command(id: String, body: JSONObject): JSONObject = request("POST", "/v1/sessions/$id/commands", body)
    override suspend fun receipt(session: String, command: String): JSONObject = request("GET", "/v1/sessions/$session/commands/$command")

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val chars = CharArray(1024 * 1024)
                    val out = StringBuilder()
                    while (true) {
                        val n = reader.read(chars, 0, minOf(chars.size, 8192))
                        if (n < 0) break
                        require(out.length + n <= 1024 * 1024) { "Response exceeds the development limit." }
                        out.append(chars, 0, n)
                    }
                    out.toString()
                } ?: "{}"
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (status !in 200..299) throw ApiException(status, json.optJSONObject("error")?.optString("code") ?: "request_failed")
                json
            } finally {
                connection.disconnect()
            }
        }
}
