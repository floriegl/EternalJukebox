package org.abimon.eternalJukebox.handlers.api

import io.vertx.core.json.JsonArray
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.abimon.eternalJukebox.*
import org.abimon.eternalJukebox.objects.*
import org.abimon.visi.io.ByteArrayDataSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

object AnalysisAPI : IAPI {
    override val mountPath: String = "/analysis"
    private val logger: Logger = LoggerFactory.getLogger("AnalysisApi")

    override fun setup(router: Router) {
        router.get("/analyse/:id").suspendingHandler(this::analyseSpotify)
        router.get("/search").suspendingHandler(AnalysisAPI::searchSpotify)
        router.post("/upload/:id").suspendingBodyHandler(this::upload, maxMb = 10)
    }

    private suspend fun analyseSpotify(context: RoutingContext) {
        if (EternalJukebox.storage.shouldStore(EnumStorageType.ANALYSIS)) {
            val id = context.pathParam("id")
            if (EternalJukebox.storage.provideIfStored("$id.json", EnumStorageType.ANALYSIS, context))
                return

            if (EternalJukebox.storage.shouldStore(EnumStorageType.UPLOADED_ANALYSIS)) {
                if (EternalJukebox.storage.provideIfStored("$id.json", EnumStorageType.UPLOADED_ANALYSIS, context))
                    return

//                return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(400).end(
//                    jsonObjectOf(
//                        "error" to "This track currently has no analysis data. Below is a tutorial on how to manually provide analysis data on Desktop.",
//                        "show_manual_analysis_info" to true,
//                        "client_uid" to context.clientInfo.userUID
//                    )
//                )
            }

            context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(400).end(
                jsonObjectOf(
                    "error" to "It is not possible to get new analysis data from Spotify. Please check the subreddit linked under 'Social' in the navigation bar for more information.",
                    "client_uid" to context.clientInfo.userUID
                )
            )
        } else {
            context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(501).end(
                jsonObjectOf(
                    "error" to "Configured storage method does not support storing ANALYSIS",
                    "client_uid" to context.clientInfo.userUID
                )
            )
        }
    }

    private suspend fun searchSpotify(context: RoutingContext) {
        val query = context.request().getParam("query") ?: "Never Gonna Give You Up"
        val results = EternalJukebox.spotify.search(query, context.clientInfo)

        context.response().end(JsonArray(results.map(JukeboxInfo::toJsonObject)))
    }

    private suspend fun upload(context: RoutingContext) {
        val id = context.pathParam("id")

        if (!EternalJukebox.storage.shouldStore(EnumStorageType.UPLOADED_ANALYSIS)) {
            return context.endWithStatusCode(502) {
                this["error"] = "This server does not support uploaded analysis"
            }
        }
        if (context.fileUploads().isEmpty()) {
            return context.endWithStatusCode(400) {
                this["error"] = "No file uploads"
            }
        }

        val uploadedFile = File(context.fileUploads().first().uploadedFileName())

        val info = EternalJukebox.spotify.getInfo(id, context.clientInfo) ?: run {
            logger.warn("[{}] Failed to get track info for {}", context.clientInfo.userUID, id)
            return context.endWithStatusCode(400) {
                this["error"] = "Failed to get track info"
            }
        }

        val track: JukeboxTrack? = try {
            val analysisObject = withContext(Dispatchers.IO) {
                EternalJukebox.jsonMapper.readValue(uploadedFile.readBytes(), Map::class.java)
            }?.toJsonObject()

            analysisObject?.let {
                JukeboxTrack(
                    info,
                    withContext(Dispatchers.IO) {
                        JukeboxAnalysis(
                            EternalJukebox.jsonMapper.readValue(
                                it.getJsonArray("sections").toString(),
                                Array<SpotifyAudioSection>::class.java
                            ),
                            EternalJukebox.jsonMapper.readValue(
                                it.getJsonArray("bars").toString(),
                                Array<SpotifyAudioBar>::class.java
                            ),
                            EternalJukebox.jsonMapper.readValue(
                                it.getJsonArray("beats").toString(),
                                Array<SpotifyAudioBeat>::class.java
                            ),
                            EternalJukebox.jsonMapper.readValue(
                                it.getJsonArray("tatums").toString(),
                                Array<SpotifyAudioTatum>::class.java
                            ),
                            EternalJukebox.jsonMapper.readValue(
                                it.getJsonArray("segments").toString(),
                                Array<SpotifyAudioSegment>::class.java
                            )
                        )
                    },
                    JukeboxSummary(it.getJsonObject("track").getDouble("duration"))
                )
            }
        } catch (e: Exception) {
            logger.warn("[{}] Failed to parse analysis file for {}", context.clientInfo.userUID, id, e)
            null
        }

        if (track == null) {
            return context.endWithStatusCode(400) {
                this["error"] = "Analysis file could not be parsed"
            }
        }
        if (track.info.duration != (track.audio_summary.duration * 1000).toInt()) {
            return context.endWithStatusCode(400) {
                this["error"] = "Track duration does not match analysis duration. This is likely due to an incorrect " +
                        "analysis file. Make sure it is for the song ${info.name} by ${info.artist}"
            }
        }

        context.response().putHeader("X-Client-UID", context.clientInfo.userUID)
            .end(track.toJsonObject())

        logger.info("[{}] Uploaded analysis for {}", context.clientInfo.userUID, id)
        withContext(Dispatchers.IO) {
            EternalJukebox.storage.store(
                "$id.json",
                EnumStorageType.UPLOADED_ANALYSIS,
                ByteArrayDataSource(track.toJsonObject().toString().toByteArray(Charsets.UTF_8)),
                "application/json",
                context.clientInfo
            )
        }
    }


    init {
        logger.info("Initialised Analysis Api")
    }
}
