package org.abimon.eternalJukebox.data.analysis

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.*
import org.abimon.eternalJukebox.EternalJukebox
import org.abimon.eternalJukebox.bearer
import org.abimon.eternalJukebox.exponentiallyBackoff
import org.abimon.eternalJukebox.objects.ClientInfo
import org.abimon.eternalJukebox.objects.JukeboxInfo
import org.abimon.eternalJukebox.objects.SpotifyError
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

@OptIn(DelicateCoroutinesApi::class)
object SpotifyAnalyser : IAnalyser {
    private val token: AtomicReference<String> = AtomicReference("")
    private val logger: Logger = LoggerFactory.getLogger("SpotifyAnalyser")

    override suspend fun search(query: String, clientInfo: ClientInfo?): Array<JukeboxInfo> {
        val array: ArrayList<JukeboxInfo> = arrayListOf()

        val success = searchWithOffset(array, query, 0, clientInfo)
        if (success)
            searchWithOffset(array, query, 10, clientInfo)

        return array.toTypedArray()
    }

    private suspend fun searchWithOffset(
        trackList: ArrayList<JukeboxInfo>,
        query: String,
        offset: Int,
        clientInfo: ClientInfo?
    ): Boolean {
        var error: SpotifyError? = null
        val success = exponentiallyBackoff(16000, 8) {
            logger.trace(
                "[{}] Attempting to search Spotify for \"{}\" with offset {}",
                clientInfo?.userUID,
                query,
                offset
            )
            val (_, response, _) = Fuel.get(
                "https://api.spotify.com/v1/search",
                listOf("q" to query, "type" to "track", "limit" to 10, "offset" to offset)
            ).bearer(token.get()).awaitStringResponseResult()
            val mapResponse =
                withContext(Dispatchers.IO) { EternalJukebox.jsonMapper.readValue(response.data, Map::class.java) }

            when (response.statusCode) {
                200 -> {
                    ((mapResponse["tracks"] as Map<*, *>)["items"] as List<*>).filter {
                        it is Map<*, *> && it.containsKey(
                            "id"
                        )
                    }.forEach {
                        val track = it as Map<*, *>
                        trackList.add(
                            JukeboxInfo(
                                "SPOTIFY",
                                track["id"] as String,
                                track["name"] as String,
                                track["name"] as String,
                                ((track["artists"] as List<*>).first() as Map<*, *>)["name"] as String,
                                "https://open.spotify.com/track/${track["id"] as String}",
                                track["duration_ms"] as Int
                            )
                        )
                    }
                    return@exponentiallyBackoff false
                }
                400 -> {
                    if (((mapResponse["error"] as Map<*, *>)["message"] as String) == "Only valid bearer authentication supported") {
                        logger.warn(
                            "[{}] Got back response code 400  with error \"Only valid bearer authentication supported\"; reloading token, backing off, and trying again",
                            clientInfo?.userUID
                        )
                        reload()
                        return@exponentiallyBackoff true
                    } else {
                        if (logger.isWarnEnabled) logger.warn(
                            "[{}] Got back response code 400 with data \"{}\"; returning INVALID_SEARCH_DATA",
                            clientInfo?.userUID,
                            response.body().asString(response.header("Content-Type").firstOrNull())
                        )
                        error = SpotifyError.INVALID_SEARCH_DATA
                        return@exponentiallyBackoff false
                    }
                }
                401 -> {
                    if (logger.isErrorEnabled) logger.error(
                        "[{}] Got back response code 401  with data \"{}\"; reloading token, backing off, and trying again",
                        clientInfo?.userUID,
                        response.body().asString(response.header("Content-Type").firstOrNull())
                    )
                    reload()
                    return@exponentiallyBackoff true
                }
                429 -> {
                    val backoff = response.header("Retry-After").firstOrNull()?.toIntOrNull() ?: 4
                    logger.warn(
                        "[{}] Got back response code 429; waiting {} seconds before trying again",
                        clientInfo?.userUID, backoff
                    )
                    delay(backoff * 1000L)
                    return@exponentiallyBackoff true
                }
                else -> {
                    if (logger.isErrorEnabled) logger.error(
                        "[{}] Got back response code {} with data \"{}\"; backing off and trying again",
                        clientInfo?.userUID,
                        response.statusCode,
                        response.body().asString(response.header("Content-Type").firstOrNull())
                    )
                    return@exponentiallyBackoff true
                }
            }
        } && error == null

        if (success)
            logger.trace("[{}] Successfully searched for \"{}\" with offset {}", clientInfo?.userUID, query, offset)
        else
            logger.trace(
                "[{}] Failed to search for \"{}\" with offset {}. Error: {}",
                clientInfo?.userUID,
                query,
                offset,
                error
            )
        return success
    }

    override suspend fun getInfo(id: String, clientInfo: ClientInfo?): JukeboxInfo? {
        var error: SpotifyError? = null
        var track: JukeboxInfo? = null

        val success = exponentiallyBackoff(16000, 8) { _ ->
            val (_, response, _) = Fuel.get("https://api.spotify.com/v1/tracks/$id").bearer(token.get())
                .awaitStringResponseResult()
            val mapResponse =
                withContext(Dispatchers.IO) { EternalJukebox.jsonMapper.readValue(response.data, Map::class.java) }

            when (response.statusCode) {
                200 -> {
                    track = JukeboxInfo(
                        "SPOTIFY",
                        mapResponse["id"] as String,
                        mapResponse["name"] as String,
                        mapResponse["name"] as String,
                        ((mapResponse["artists"] as List<*>).first() as Map<*, *>)["name"] as String,
                        "https://open.spotify.com/track/${mapResponse["id"] as String}",
                        mapResponse["duration_ms"] as Int
                    )
                    return@exponentiallyBackoff false
                }
                400 -> {
                    if (((mapResponse["error"] as Map<*, *>)["message"] as String) == "Only valid bearer authentication supported") {
                        logger.error(
                            "[{}] Got back response code 400  with error \"Only valid bearer authentication supported\"; reloading token, backing off, and trying again",
                            clientInfo?.userUID
                        )
                        reload()
                        return@exponentiallyBackoff true
                    } else {
                        logger.error(
                            "[{}] Got back response code 400 with data \"{}\"; returning INVALID_SEARCH_DATA",
                            clientInfo?.userUID,
                            response.body().asString(response.header("Content-Type").firstOrNull())
                        )
                        error = SpotifyError.INVALID_SEARCH_DATA
                        return@exponentiallyBackoff false
                    }
                }
                401 -> {
                    logger.error(
                        "[{}] Got back response code 401 with data \"{}\"; reloading token, backing off, and trying again",
                        clientInfo?.userUID,
                        response.body().asString(response.header("Content-Type").firstOrNull())
                    )
                    reload()
                    return@exponentiallyBackoff true
                }
                429 -> {
                    val backoff = response.header("Retry-After").firstOrNull()?.toIntOrNull() ?: 4
                    logger.warn(
                        "[{}] Got back response code 429; waiting {} seconds before trying again",
                        clientInfo?.userUID,
                        backoff
                    )
                    delay(backoff * 1000L)
                    return@exponentiallyBackoff true
                }
                else -> {
                    logger.warn(
                        "[{}] Got back response code {} with data \"{}\"; backing off and trying again",
                        clientInfo?.userUID,
                        response.statusCode,
                        response.body().asString(response.header("Content-Type").firstOrNull())
                    )
                    return@exponentiallyBackoff true
                }
            }
        } && error == null

        if (success)
            logger.trace("[{}] Successfully obtained info for {} off of Spotify", clientInfo?.userUID, id)
        else
            logger.trace("[{}] Failed to obtain info for {}. Error: {}", clientInfo?.userUID, id, error)

        return track
    }

    private suspend fun reload(): SpotifyError? {
        var error: SpotifyError? = null
        val success = exponentiallyBackoff(64000, 8) { attempt ->
            logger.trace("Attempting to reload Spotify Token; Attempt {}", attempt)
            val (_, response, _) =
                Fuel.post("https://accounts.spotify.com/api/token")
                    .header("Content-Type", "application/x-www-form-urlencoded").body("grant_type=client_credentials")
                    .authentication().basic(
                        EternalJukebox.config.spotifyClient
                            ?: run {
                                error = SpotifyError.NO_AUTH_DETAILS
                                return@exponentiallyBackoff false
                            }, EternalJukebox.config.spotifySecret ?: run {
                            error = SpotifyError.NO_AUTH_DETAILS
                            return@exponentiallyBackoff false
                        }).awaitStringResponseResult()

            when (response.statusCode) {
                200 -> {
                    token.set(JsonObject(String(response.data, Charsets.UTF_8)).getString("access_token"))
                    return@exponentiallyBackoff false
                }
                400 -> {
                    logger.error(
                        "Got back response code 400 with data \"{}\"; returning INVALID_AUTH_DETAILS",
                        response.body().asString(response.header("Content-Type").firstOrNull())
                    )
                    error = SpotifyError.INVALID_AUTH_DETAILS
                    return@exponentiallyBackoff false
                }
                401 -> {
                    logger.error(
                        "Got back response code 401  with data \"{}\"; returning INVALID_AUTH_DETAILS",
                        response.body().asString(response.header("Content-Type").firstOrNull())
                    )
                    error = SpotifyError.INVALID_AUTH_DETAILS
                    return@exponentiallyBackoff false
                }
                429 -> {
                    val backoff = response.header("Retry-After").firstOrNull()?.toIntOrNull() ?: 4
                    logger.warn(
                        "Got back response code 429; waiting {} seconds before trying again",
                        backoff
                    )
                    delay(backoff * 1000L)
                    return@exponentiallyBackoff true
                }
                else -> {
                    logger.warn(
                        "Got back response code {} with data \"{}\"; backing off and trying again",
                        response.statusCode,
                        response.body().asString(response.header("Content-Type").firstOrNull())
                    )
                    return@exponentiallyBackoff true
                }
            }
        } && error == null

        if (!success)
            logger.trace("Failed to reload the Spotify token. Error: {}", error)
        else
            logger.trace("Successfully reloaded the Spotify token")
        return error
    }

    init {
        GlobalScope.launch {
            while (isActive) {
                reload()
                delay(3000 * 1000)
            }
        }
    }
}
