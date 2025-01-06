package org.abimon.eternalJukebox

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object MediaWrapper {
    @Suppress("ClassName")
    object ffmpeg {
        val installed: Boolean
            get() {
                val process: Process = try {
                    ProcessBuilder().command("ffmpeg", "-version").start()
                } catch (e: IOException) {
                    return false
                }

                process.waitFor(5, TimeUnit.SECONDS)

                return String(process.inputStream.readBytes(), Charsets.UTF_8).startsWith("ffmpeg version")
            }

        fun convert(input: File, output: File, error: File): Boolean {
            val ffmpegProcess = ProcessBuilder().command("ffmpeg", "-i", input.absolutePath, output.absolutePath).redirectErrorStream(true).redirectOutput(error).start()

            if (ffmpegProcess.waitFor(60, TimeUnit.SECONDS)) {
                return ffmpegProcess.exitValue() == 0
            }

            ffmpegProcess.destroyForcibly()
            return false
        }
    }
}
