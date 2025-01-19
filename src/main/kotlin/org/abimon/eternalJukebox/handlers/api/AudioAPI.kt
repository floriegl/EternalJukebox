package org.abimon.eternalJukebox.handlers.api

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.abimon.eternalJukebox.*
import org.abimon.eternalJukebox.data.audio.YoutubeAudioSource
import org.abimon.eternalJukebox.objects.EnumStorageType
import org.abimon.eternalJukebox.objects.JukeboxInfo
import org.abimon.visi.io.FileDataSource
import org.abimon.visi.security.md5Hash
import org.abimon.visi.security.sha512Hash
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit

object AudioAPI : IAPI {
    override val mountPath: String = "/audio"
    private val logger: Logger = LoggerFactory.getLogger("AudioApi")

    private val format: String
        get() = EternalJukebox.config.audioSourceOptions["AUDIO_FORMAT"] as? String ?: "m4a"
    private val uuid: String
        get() = UUID.randomUUID().toString()

    private val base64Encoder: Base64.Encoder by lazy { Base64.getUrlEncoder() }

    private const val FALLBACK_ID = "7GhIk7Il098yCjg4BQjzvb"

    override fun setup(router: Router) {
        router.get("/jukebox/:id").suspendingHandler(AudioAPI::jukeboxAudio)
        router.get("/jukebox/:id/location").suspendingHandler(AudioAPI::jukeboxLocation)
        router.get("/external").suspendingHandler(AudioAPI::externalAudio)
        router.post("/upload").suspendingBodyHandler(this::upload, maxMb = 25)
    }

    private suspend fun jukeboxAudio(context: RoutingContext) {
        if (!EternalJukebox.storage.shouldStore(EnumStorageType.AUDIO)) {
            return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(501).end(
                jsonObjectOf(
                    "error" to "Configured storage method does not support storing AUDIO",
                    "client_uid" to context.clientInfo.userUID
                )
            )
        }

        val id = context.pathParam("id")

        val audioOverride =
            withContext(Dispatchers.IO) { EternalJukebox.database.provideAudioTrackOverride(id, context.clientInfo) }
        if (audioOverride != null)
            return context.response().redirect(
                "/api/audio/external?url=${
                    withContext(Dispatchers.IO) {
                        URLEncoder.encode(audioOverride, "UTF-8")
                    }
                }"
            )

        val update = context.request().getParam("update")?.toBoolean() ?: false
        if (!update && EternalJukebox.storage.provideIfStored("$id.$format", EnumStorageType.AUDIO, context)) {
            return
        }

        if (update)
            logger.trace(
                "[{}] {} is requesting an update for {}",
                context.clientInfo.userUID, context.clientInfo.remoteAddress, id
            )

        val track = getSongInfoOrSetError(id, context) ?: return

        if (EternalJukebox.audio?.provide(track, context) != true) {
            context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(400).end(
                jsonObjectOf(
                    "error" to "Audio is null",
                    "client_uid" to context.clientInfo.userUID
                )
            )
        }
    }

    private suspend fun jukeboxLocation(context: RoutingContext) {
        val id = context.pathParam("id")

        val audioOverride =
            withContext(Dispatchers.IO) { EternalJukebox.database.provideAudioTrackOverride(id, context.clientInfo) }
        if (audioOverride != null)
            return context.endWithStatusCode(200) { if (!audioOverride.startsWith("upl")) this["url"] = audioOverride }

        val track = getSongInfoOrSetError(id, context) ?: return

        val url = EternalJukebox.audio?.provideLocation(track, context.clientInfo)

        context.endWithStatusCode(200) { if (url != null) this["url"] = url.toExternalForm() }
    }

    private suspend fun getSongInfoOrSetError(id: String, context: RoutingContext): JukeboxInfo? =
        EternalJukebox.spotify.getInfo(id, context.clientInfo) ?: run {
            logger.warn("[{}] No track info for {}; returning 400", context.clientInfo.userUID, id)
            context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(400).end(
                jsonObjectOf(
                    "error" to "Track info not found for $id",
                    "client_uid" to context.clientInfo.userUID
                )
            )
            null
        }

    // url -> fallbackURL -> fallbackID
    private suspend fun externalAudio(context: RoutingContext) {
        val url = context.request().getParam("url")?.trim()
            ?: return context.endWithStatusCode(400) { this["error"] = "No URL provided" }

        if (url.startsWith("upl:")) {
            if (!EternalJukebox.storage.shouldStore(EnumStorageType.UPLOADED_AUDIO)) {
                logger.warn(
                    "[{}] Rerouting external audio request of URL {}; this server does not support uploaded audio",
                    context.clientInfo.userUID,
                    url
                )
                return context.reroute(
                    "/api" + mountPath + "/jukebox/${context.request().getParam("fallbackID") ?: FALLBACK_ID}"
                )
            }

            val hash = url.substringAfter("upl:")
            if (EternalJukebox.storage.provideIfStored("$hash.$format", EnumStorageType.UPLOADED_AUDIO, context))
                return

            logger.warn(
                "[{}] Rerouting external audio request of URL {}; no storage for {}.{}",
                context.clientInfo.userUID, url, hash, format
            )
            return context.reroute(
                "/api$mountPath/jukebox/${context.request().getParam("fallbackID") ?: FALLBACK_ID}"
            )
        }

        @Suppress("HttpUrlsUsage")
        val response: Response? =
            if (url.contains("://") && !url.startsWith("http://") && !url.startsWith("https://")) {
                logger.info("[{}] Received URL with invalid protocol {}", context.clientInfo.userUID, url)
                null
            } else {
                withContext(Dispatchers.IO) { Fuel.headOrGet(url).second }
            }

        if (response != null && response.statusCode < 300) {
            val mime = response.headers["Content-Type"].firstOrNull()

            if (mime != null && mime.startsWith("audio"))
                return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).redirect(url)


            if (EternalJukebox.storage.shouldStore(EnumStorageType.EXTERNAL_AUDIO)) {
                val b64 = base64Encoder.encodeToString(url.toByteArray(Charsets.UTF_8)).md5Hash()

                val update = context.request().getParam("update")?.toBoolean() ?: false
                if (!update && EternalJukebox.storage.provideIfStored(
                        "$b64.$format", EnumStorageType.EXTERNAL_AUDIO, context
                    )
                ) return

                if (update)
                    logger.trace(
                        "[{}] {} is requesting an update for {} / {}",
                        context.clientInfo.userUID, context.clientInfo.remoteAddress, url, b64
                    )

                val tmpFile = File("$uuid.tmp")
                val tmpLog = File("$b64-$uuid.log")
                val ffmpegLog = File("$b64-$uuid.log")
                val endGoalTmp = File(tmpFile.absolutePath.replace(".tmp", ".tmp.$format"))

                try {
                    withContext(Dispatchers.IO) {
                        val downloadProcess =
                            ProcessBuilder().command(ArrayList(YoutubeAudioSource.command).apply {
                                add(url)
                                add(tmpFile.absolutePath)
                                add(YoutubeAudioSource.format)
                            }).redirectErrorStream(true).redirectOutput(tmpLog).start()

                        if (!downloadProcess.waitFor(90, TimeUnit.SECONDS)) {
                            downloadProcess.destroyForcibly().waitFor()
                            logger.warn(
                                "[{}] Forcibly destroyed the download process for {}",
                                context.clientInfo.userUID, url
                            )
                        }
                    }

                    var ffmpegSuccess = false
                    if (!endGoalTmp.exists()) {
                        try {
                            logger.info(
                                "[{}] {} does not exist, attempting to convert with ffmpeg",
                                context.clientInfo.userUID, endGoalTmp
                            )

                            if (!tmpFile.exists()) {
                                val lastLine = tmpLog.useLines { seq -> seq.last() }
                                return logger.error(
                                    "[{}] {} does not exist, what happened? (Last line was {})",
                                    context.clientInfo.userUID, tmpFile, lastLine
                                )
                            }

                            if (MediaWrapper.ffmpeg.installed) {
                                ffmpegSuccess = convertWithFfmpeg(tmpFile, endGoalTmp, ffmpegLog, context)
                            } else
                                return logger.error(
                                    "[{}] ffmpeg not installed, nothing we can do",
                                    context.clientInfo.userUID
                                )
                        } finally {
                            if (!ffmpegSuccess) {
                                context.response().putHeader("X-Client-UID", context.clientInfo.userUID)
                                    .setStatusCode(500).end()
                            }
                        }
                    }

                    withContext(Dispatchers.IO) {
                        endGoalTmp.useThenDelete {
                            EternalJukebox.storage.store(
                                "$b64.${YoutubeAudioSource.format}",
                                EnumStorageType.EXTERNAL_AUDIO,
                                FileDataSource(it),
                                YoutubeAudioSource.mimes[YoutubeAudioSource.format]
                                    ?: "audio/mpeg",
                                context.clientInfo
                            )
                        }
                    }

                    if (EternalJukebox.storage.safeProvide("$b64.$format", EnumStorageType.EXTERNAL_AUDIO, context))
                        return
                } finally {
                    tmpFile.guaranteeDelete()
                    withContext(Dispatchers.IO) {
                        tmpLog.useThenDelete {
                            EternalJukebox.storage.store(
                                it.name,
                                EnumStorageType.LOG,
                                FileDataSource(it),
                                "text/plain",
                                context.clientInfo
                            )
                        }
                    }
                    withContext(Dispatchers.IO) {
                        ffmpegLog.useThenDelete {
                            EternalJukebox.storage.store(
                                it.name,
                                EnumStorageType.LOG,
                                FileDataSource(it),
                                "text/plain",
                                context.clientInfo
                            )
                        }
                    }
                    withContext(Dispatchers.IO) {
                        endGoalTmp.useThenDelete {
                            EternalJukebox.storage.store(
                                "$b64.$format",
                                EnumStorageType.EXTERNAL_AUDIO,
                                FileDataSource(it),
                                YoutubeAudioSource.mimes[format]
                                    ?: "audio/mpeg",
                                context.clientInfo
                            )
                        }
                    }
                }
            }
        }

        context.reroute(
            "/api$mountPath/jukebox/${context.request().getParam("fallbackID") ?: FALLBACK_ID}"
        )
    }

    private suspend fun upload(context: RoutingContext) {
        if (!EternalJukebox.storage.shouldStore(EnumStorageType.UPLOADED_AUDIO)) {
            return context.endWithStatusCode(502) {
                this["error"] = "This server does not support uploaded audio"
            }
        }
        if (!MediaWrapper.ffmpeg.installed) {
            logger.error("[{}] ffmpeg not installed for audio upload", context.clientInfo.userUID)
            return context.endWithStatusCode(502) {
                this["error"] = "This server does not support uploaded audio"
            }
        }
        if (context.fileUploads().isEmpty()) {
            return context.endWithStatusCode(400) {
                this["error"] = "No file uploads"
            }
        }

        val file = context.fileUploads().first()

        val ffmpegLog = File("${file.fileName()}-$uuid.log")
        val starting = File(file.uploadedFileName())
        val ending = File("$uuid.$format")

        try {
            if (!convertWithFfmpeg(starting, ending, ffmpegLog, context))
                return
        } finally {
            withContext(Dispatchers.IO) {
                ffmpegLog.useThenDelete {
                    EternalJukebox.storage.store(
                        it.name,
                        EnumStorageType.LOG,
                        FileDataSource(it),
                        "text/plain",
                        context.clientInfo
                    )
                }
            }

            val hash = ending.useThenDelete { endingFile ->
                FileInputStream(endingFile)
                    .use { stream -> stream.sha512Hash() }
                    .also {
                        withContext(Dispatchers.IO) {
                            EternalJukebox.storage.store(
                                "$it.$format",
                                EnumStorageType.UPLOADED_AUDIO,
                                FileDataSource(endingFile),
                                YoutubeAudioSource.mimes[format] ?: "audio/mpeg",
                                context.clientInfo
                            )
                        }
                    }
            }

            if (hash != null) {
                context.endWithStatusCode(201) { this["id"] = hash }
            } else {
                context.endWithStatusCode(502) { this["error"] = "Failed to convert audio" }
            }
        }
    }

    private suspend fun convertWithFfmpeg(
        inputFile: File,
        output: File,
        log: File,
        context: RoutingContext
    ): Boolean {
        if (withContext(Dispatchers.IO) { !MediaWrapper.ffmpeg.convert(inputFile, output, log) }) {
            logger.error(
                "[{}] Failed to convert {} to {}. Check {} for details",
                context.clientInfo.userUID, inputFile, output, log.name
            )
            return false
        }


        if (!output.exists()) {
            logger.error(
                "[{}] {} does not exist. Check {} for details",
                context.clientInfo.userUID, output, log.name
            )
            return false
        }

        return true
    }

    private fun Fuel.headOrGet(url: String): Pair<Request, Response> {
        val urlWithProtocol = if (!url.contains("://")) "https://$url" else url
        val (headRequest, headResponse) = head(urlWithProtocol).response()

        if (headResponse.statusCode == 404 || headResponse.statusCode == 405) {
            val (getRequest, getResponse) = get(urlWithProtocol).response()

            if (headResponse.statusCode != getResponse.statusCode && headResponse.statusCode != 405)
                logger.warn(
                    "Request to {} gave a different response between HEAD and GET request ({} vs {})",
                    urlWithProtocol,
                    headResponse.statusCode,
                    getResponse.statusCode
                )

            return getRequest to getResponse
        }

        return headRequest to headResponse
    }

    init {
        logger.info("Initialised Audio Api")
    }
}
