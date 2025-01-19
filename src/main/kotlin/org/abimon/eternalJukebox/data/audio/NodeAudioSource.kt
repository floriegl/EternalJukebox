package org.abimon.eternalJukebox.data.audio

import io.vertx.ext.web.RoutingContext
import org.abimon.eternalJukebox.clientInfo
import org.abimon.eternalJukebox.data.NodeSource
import org.abimon.eternalJukebox.objects.JukeboxInfo

@Suppress("UNCHECKED_CAST")
object NodeAudioSource: NodeSource(), IAudioSource {
    @Suppress("JoinDeclarationAndAssignment")
    override val nodeHosts: Array<String>

    override suspend fun provide(info: JukeboxInfo, context: RoutingContext): Boolean = provide("audio/${info.id}?user_uid=${context.clientInfo.userUID}", context)

    init {
        nodeHosts = if (audioSourceOptions.containsKey("NODE_HOST"))
            arrayOf(audioSourceOptions["NODE_HOST"] as? String ?: throw IllegalArgumentException("${audioSourceOptions["NODE_HOST"]}  is not of type 'String' (is ${audioSourceOptions["NODE_HOST"]?.javaClass}"))
        else if (audioSourceOptions.containsKey("NODE_HOSTS")) {
            (audioSourceOptions["NODE_HOSTS"] as? List<String>)?.toTypedArray() ?: throw throw IllegalArgumentException("${audioSourceOptions["NODE_HOSTS"]}  is not of type 'List<String>' (is ${audioSourceOptions["NODE_HOSTS"]?.javaClass}")
        } else
            throw IllegalArgumentException("No hosts assigned for NodeAudioSource")
    }
}
