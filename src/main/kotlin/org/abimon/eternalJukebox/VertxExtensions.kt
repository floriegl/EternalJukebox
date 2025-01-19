package org.abimon.eternalJukebox

import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Route
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.abimon.eternalJukebox.objects.ClientInfo
import org.abimon.eternalJukebox.objects.ConstantValues

fun HttpServerResponse.end(json: JsonArray) = putHeader("Content-Type", "application/json").end(json.toString())
fun HttpServerResponse.end(json: JsonObject) = putHeader("Content-Type", "application/json").end(json.toString())

fun RoutingContext.endWithStatusCode(statusCode: Int, init: JsonObject.() -> Unit) {
    val json = JsonObject()
    json.init()

    this.response().setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .putHeader("X-Client-UID", clientInfo.userUID)
            .end(json.toString())
}

fun HttpServerResponse.redirect(url: String): Unit = putHeader("Location", url).setStatusCode(307).end()

val RoutingContext.clientInfo: ClientInfo
    get() {
        if (ConstantValues.CLIENT_INFO in data() && data()[ConstantValues.CLIENT_INFO] is ClientInfo)
            return data()[ConstantValues.CLIENT_INFO] as ClientInfo

        val info = ClientInfo(this)

        data()[ConstantValues.CLIENT_INFO] = info

        return info
    }

operator fun JsonObject.set(key: String, value: Any): JsonObject = put(key, value)

fun Route.suspendingBodyHandler(handler: suspend (RoutingContext) -> Unit, maxMb: Long): Route =
    handler(BodyHandler.create().setBodyLimit(maxMb * 1000 * 1000).setDeleteUploadedFilesOnEnd(true))
        .suspendingHandler(handler)

@OptIn(DelicateCoroutinesApi::class)
fun Route.suspendingHandler(handler: suspend (RoutingContext) -> Unit): Route =
    handler { ctx ->
        GlobalScope.launch(ctx.vertx().dispatcher()) {
            handler(ctx)
        }
    }
