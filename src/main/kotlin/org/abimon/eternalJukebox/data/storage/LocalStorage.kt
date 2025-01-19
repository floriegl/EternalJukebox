package org.abimon.eternalJukebox.data.storage

import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.abimon.eternalJukebox.EternalJukebox
import org.abimon.eternalJukebox.clientInfo
import org.abimon.eternalJukebox.objects.ClientInfo
import org.abimon.eternalJukebox.objects.EnumStorageType
import org.abimon.visi.io.DataSource
import java.io.File
import java.io.FileOutputStream
import java.util.*

object LocalStorage : IStorage {
    private val storageLocations: Map<EnumStorageType, File> = EnumStorageType.entries.associateWith { type ->
        File(
            EternalJukebox.config.storageOptions["${type.name}_FOLDER"] as? String
                ?: type.name.lowercase(Locale.getDefault())
        )
    }

    override fun shouldStore(type: EnumStorageType): Boolean = !disabledStorageTypes.contains(type)

    override suspend fun store(name: String, type: EnumStorageType, data: DataSource, mimeType: String, clientInfo: ClientInfo?): Boolean {
        return withContext(Dispatchers.IO) {
            val file = File(storageLocations[type]!!, name)
            FileOutputStream(file).use { fos ->
                data.use { inputStream ->
                    inputStream.copyTo(fos)
                }
            }
            true
        }
    }

    override suspend fun provide(name: String, type: EnumStorageType, context: RoutingContext): Boolean {
        val file = File(storageLocations[type]!!, name)
        if(file.exists()) {
            context.response().putHeader("X-Client-UID", context.clientInfo.userUID).sendFile(file.absolutePath)
            return true
        }

        return false
    }

    override suspend fun isStored(name: String, type: EnumStorageType): Boolean = File(storageLocations[type]!!, name).exists()

    init {
        storageLocations.values.forEach { if(!it.exists()) it.mkdirs() }
    }
}
