package org.abimon.eternalJukebox.data.audio

import io.vertx.ext.web.RoutingContext
import org.abimon.eternalJukebox.EternalJukebox
import org.abimon.eternalJukebox.objects.ClientInfo
import org.abimon.eternalJukebox.objects.JukeboxInfo
import java.net.URL

@FunctionalInterface
interface IAudioSource {
    val audioSourceOptions
        get() = EternalJukebox.config.audioSourceOptions
    /**
     * Provide the audio data for a required song to the routing context.
     * Returns true if handled; false otherwise.
     */
    suspend fun provide(info: JukeboxInfo, context: RoutingContext): Boolean

    /**
     * Provide a location for a required song
     * The provided location may not be a direct download link, and may not contain valid audio data.
     * The provided location, however, should be a link to said song where possible, or return null if nothing could be found.
     */
    suspend fun provideLocation(info: JukeboxInfo, clientInfo: ClientInfo?): URL? = null
}
