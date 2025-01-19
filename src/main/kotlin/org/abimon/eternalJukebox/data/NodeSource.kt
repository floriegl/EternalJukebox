package org.abimon.eternalJukebox.data

import com.github.kittinunf.fuel.Fuel
import io.vertx.ext.web.RoutingContext
import org.abimon.eternalJukebox.redirect
import java.util.*

abstract class NodeSource {
    abstract val nodeHosts: Array<String>

    private val rng: Random = Random()

    fun provide(path: String, context: RoutingContext): Boolean {
        val starting = rng.nextInt(nodeHosts.size)

        for (i in nodeHosts.indices) {
            val host = nodeHosts[(starting + i) % nodeHosts.size]
            val (_, healthy) = Fuel.get("$host/api/node/healthy").timeout(5 * 1000).response()
            if (healthy.statusCode == 200) {
                context.response().redirect("$host/api/node/$path")
                return true
            }
        }

        return false
    }
}
