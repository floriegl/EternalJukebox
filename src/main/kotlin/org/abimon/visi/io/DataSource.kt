package org.abimon.visi.io

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

interface DataSource {
    /**
     * Get an input stream associated with this data source.
     */
    val inputStream: InputStream
    val data: ByteArray
    val size: Long
    
    fun <T> use(action: (InputStream) -> T): T = inputStream.use(action)
}

class FileDataSource(val file: File) : DataSource {

    override val data: ByteArray
        get() = file.readBytes()

    override val inputStream: InputStream
        get() = FileInputStream(file)

    override val size: Long
        get() = file.length()
}

class ByteArrayDataSource(override val data: ByteArray): DataSource {
    override val inputStream: InputStream
        get() = ByteArrayInputStream(data)
    override val size: Long = data.size.toLong()
}
