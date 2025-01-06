package org.abimon.eternalJukebox.handlers.api

import com.jakewharton.fliptables.FlipTable
import com.sun.management.OperatingSystemMXBean
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.*
import org.abimon.eternalJukebox.*
import org.abimon.eternalJukebox.objects.ClientInfo
import org.abimon.eternalJukebox.objects.EnumAnalyticType
import org.abimon.visi.lang.usedMemory
import org.abimon.visi.time.timeDifference
import java.lang.management.ManagementFactory
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.util.*

@OptIn(DelicateCoroutinesApi::class)
object SiteAPI: IAPI {
    override val mountPath: String = "/site"
    private val startupTime: LocalDateTime = LocalDateTime.now()

    private val memoryFormat = DecimalFormat("####.##")
    private val cpuFormat = DecimalFormat("#.####")

    private val osBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean

    override fun setup(router: Router) {
        router.get("/healthy").handler { it.response().end("Up for ${startupTime.timeDifference()}") }
        router.get("/usage").handler(SiteAPI::usage)

        router.get("/expand/:id").suspendingHandler(SiteAPI::expand)
        router.get("/expand/:id/redirect").suspendingHandler(SiteAPI::expandAndRedirect)
        router.post("/shrink").suspendingBodyHandler(SiteAPI::shrink, maxMb = 1)

        router.get("/popular/:service").suspendingHandler(this::popular)
    }

    private suspend fun popular(context: RoutingContext) {
        val service = context.pathParam("service")
        val count = context.request().getParam("count")?.toIntOrNull() ?: context.request().getParam("limit")?.toIntOrNull() ?: 30

        context.response().putHeader("X-Client-UID", context.clientInfo.userUID).end(JsonArray(withContext(Dispatchers.IO) { EternalJukebox.database.providePopularSongs(service, count, context.clientInfo) }))
    }

    private fun usage(context: RoutingContext) {
        val rows = arrayOf(
            "Uptime" to startupTime.timeDifference(),
            "Total Memory" to "${memoryFormat.format(Runtime.getRuntime().totalMemory() / 1000000.0)} MB",
            "Free Memory" to "${memoryFormat.format(Runtime.getRuntime().freeMemory() / 1000000.0)} MB",
            "Used Memory" to "${memoryFormat.format(Runtime.getRuntime().usedMemory() / 1000000.0)} MB",
            "CPU Load (Process)" to "${cpuFormat.format(osBean.processCpuLoad * 100)}%",
            "CPU Load (System)" to "${cpuFormat.format(osBean.systemCpuLoad * 100)}%"
        )

        context.response()
            .putHeader("X-Client-UID", context.clientInfo.userUID)
            .putHeader("Content-Type", "text/plain; charset=UTF-8")
            .end(FlipTable.of(arrayOf("Key", "Value"), rows.map { (one, two) -> arrayOf(one, two) }.toTypedArray()))
    }

    private suspend fun expand(context: RoutingContext) {
        val id = context.pathParam("id")
        val clientInfo = context.clientInfo
        val expanded = expand(id, clientInfo) ?: return context.response().putHeader("X-Client-UID", clientInfo.userUID).setStatusCode(400).end(jsonObjectOf("error" to "No short ID stored", "id" to id))
        context.response().end(expanded)
    }

    private suspend fun expandAndRedirect(context: RoutingContext) {
        val id = context.pathParam("id")
        val clientInfo = context.clientInfo
        val expanded = expand(id, clientInfo) ?: return context.response().putHeader("X-Client-UID", clientInfo.userUID).setStatusCode(400).end(jsonObjectOf("error" to "No short ID stored", "id" to id))

        context.response().redirect(expanded.getString("url"))
    }

    private suspend fun expand(id: String, clientInfo: ClientInfo): JsonObject? {
        val params =
            withContext(Dispatchers.IO) { EternalJukebox.database.expandShortURL(id, clientInfo) } ?: return null
        val paramsMap = params.map { pair -> pair.split('=', limit = 2) }.filter { pair -> pair.size == 2 }
            .associateTo(HashMap()) { pair -> Pair(pair[0], pair[1]) }

        val service = paramsMap.remove("service") ?: "jukebox"
        val response = JsonObject()

        when (service.lowercase(Locale.getDefault())) {
            "jukebox" -> response["url"] =
                "/jukebox_go.html?${paramsMap.entries.joinToString("&") { (key, value) -> "$key=$value" }}"

            "canonizer" -> response["url"] =
                "/canonizer_go.html?${paramsMap.entries.joinToString("&") { (key, value) -> "$key=$value" }}"

            else -> response["url"] = "/jukebox_index.html"
        }

        val trackInfo = EternalJukebox.spotify.getInfo(paramsMap["id"] ?: "4uLU6hMCjMI75M1A2tKUQC", clientInfo)

        response["song"] = EternalJukebox.jsonMapper.convertValue(trackInfo, Map::class.java)
        response["params"] = paramsMap

        return response
    }

    private suspend fun shrink(context: RoutingContext) {
        val params = context.bodyAsString.split('&').toTypedArray()
        val id = withContext(Dispatchers.IO) { EternalJukebox.database.provideShortURL(params, context.clientInfo) }
        context.response().putHeader("X-Client-UID", context.clientInfo.userUID).end(jsonObjectOf("id" to id, "params" to params))
    }

    init {
        GlobalScope.launch {
            while (isActive) {
                val time = System.currentTimeMillis()

                try {
                    EternalJukebox.analyticsProviders.forEach { provider ->
                        EternalJukebox.analytics.storeMultiple(time, provider.provideMultiple(time, *EnumAnalyticType.VALUES.filter { type -> provider.shouldProvide(type) }.toTypedArray()).map { (type, data) -> type to data })
                    }
                } catch (th: Throwable) {
                    th.printStackTrace()
                }

                delay(EternalJukebox.config.usageWritePeriod)
            }
        }
    }
}
