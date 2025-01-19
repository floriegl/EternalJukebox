package org.abimon.eternalJukebox.data.storage

import io.vertx.ext.web.RoutingContext
import org.abimon.eternalJukebox.EternalJukebox
import org.abimon.eternalJukebox.clientInfo
import org.abimon.eternalJukebox.objects.ClientInfo
import org.abimon.eternalJukebox.objects.EnumStorageType
import org.abimon.visi.io.DataSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

interface IStorage {
    val storageOptions
        get() = EternalJukebox.config.storageOptions

    val disabledStorageTypes: List<EnumStorageType>
        get() = storageOptions.let { storageOptionMap ->
            EnumStorageType.entries.filter { enumStorageType -> storageOptionMap["${enumStorageType.name.uppercase(Locale.getDefault())}_IS_DISABLED"]?.toString()?.toBoolean() ?: false }
        }

    val logger: Logger
        get() = LoggerFactory.getLogger(this::class.java)

    /**
     * Should we store this type of storage?
     */
    fun shouldStore(type: EnumStorageType): Boolean

    /**
     * Store [data] under [name], as type [type]
     * Returns true if successfully stored; false otherwise
     */
    suspend fun store(name: String, type: EnumStorageType, data: DataSource, mimeType: String, clientInfo: ClientInfo?): Boolean

    /**
     * Provide previously stored data of name [name] and type [type] to the routing context.
     * Returns true if handled; false otherwise.
     */
    suspend fun provide(name: String, type: EnumStorageType, context: RoutingContext): Boolean

    suspend fun isStored(name: String, type: EnumStorageType): Boolean

    suspend fun safeProvide(name: String, type: EnumStorageType, context: RoutingContext): Boolean {
        if (context.response().closed()) {
            logger.debug(
                "[{}] User closed connection before the response could be sent for {} of type {}",
                context.clientInfo.userUID,
                name,
                type
            )
            return true
        }
        return provide(name, type, context)
    }

    suspend fun provideIfStored(name: String, type: EnumStorageType, context: RoutingContext): Boolean {
        if(isStored(name, type)) {
            if (safeProvide(name, type, context)) {
                return true
            } else {
                logger.warn("Failed to provide stored data $name of type $type")
            }
        }
        return false
    }
}
