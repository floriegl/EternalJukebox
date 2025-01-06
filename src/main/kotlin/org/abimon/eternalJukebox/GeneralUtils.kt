package org.abimon.eternalJukebox

import io.vertx.core.json.JsonObject
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

/**
 * @param task return true if we need to back off
 */
suspend fun exponentiallyBackoff(maximumBackoff: Long, maximumTries: Long, task: suspend (Long) -> Boolean): Boolean {
    if (!task(0))
        return true

    for (i in 0 until maximumTries) {
        if (!task(i))
            return true

        delay(min((2.0.pow(i.toDouble()) + kotlin.random.Random.nextInt(1000)).toLong(), maximumBackoff))
    }

    return false
}

fun Any.toJsonObject(): JsonObject = JsonObject(EternalJukebox.jsonMapper.writeValueAsString(this))

fun jsonObjectOf(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.toMap())

/**
 * Perform an action with this file if it exists, and then delete it.
 * Returns null if the file does not exist
 */
inline fun <T> File.useThenDelete(action: (File) -> T): T? {
    try {
        return if (exists())
            action(this)
        else
            null
    } finally {
        guaranteeDelete()
    }
}

val logger: Logger = LoggerFactory.getLogger("Miscellaneous")

@OptIn(DelicateCoroutinesApi::class)
fun File.guaranteeDelete() {
    delete()
    if (exists()) {
        logger.trace("{} was not deleted successfully; deleting on exit and starting coroutine", this)
        deleteOnExit()

        GlobalScope.launch {
            val rng = Random()
            var i = 0
            while (isActive && exists()) {
                delete()
                if (!exists()) {
                    logger.trace("Finally deleted {} after {} attempts", this, i)
                }

                delay(min((2.0.pow((i++).toDouble()) + rng.nextInt(1000)).toLong(), 64000))
            }
        }
    }
}

val KClass<*>.simpleClassName: String
    get() = simpleName ?: jvmName.substringAfterLast('.')

fun ScheduledExecutorService.scheduleAtFixedRate(
    initialDelay: Long,
    every: Long,
    unit: TimeUnit = TimeUnit.MILLISECONDS,
    op: () -> Unit
): ScheduledFuture<*> = this.scheduleAtFixedRate(op, initialDelay, every, unit)

fun ScheduledExecutorService.schedule(delay: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, op: () -> Unit): ScheduledFuture<*> = this.schedule(op, delay, unit)

fun String.toBase64LongOrNull(): Long? {
    var i = 0
    val len: Int = length
    val limit: Long = -Long.Companion.MAX_VALUE

    return if (len > 0) {
        val multmin: Long = limit / 64
        var result: Long = 0
        while (i < len) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            val digit: Int = EternalJukebox.BASE_64_URL.indexOf(get(i++))
            if (digit < 0 || result < multmin) {
                return null
            }
            result *= 64
            if (result < limit + digit) {
                return null
            }
            result -= digit.toLong()
        }
        -result
    } else {
        return null
    }
}
fun String.toBase64Long(): Long {
    var i = 0
    val len: Int = length
    val limit: Long = -Long.Companion.MAX_VALUE

    return if (len > 0) {
        val multmin: Long = limit / 64
        var result: Long = 0
        while (i < len) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            val digit: Int = EternalJukebox.BASE_64_URL.indexOf(get(i++))
            if (digit < 0 || result < multmin) {
                throw NumberFormatException()
            }
            result *= 64
            if (result < limit + digit) {
                throw NumberFormatException()
            }
            result -= digit.toLong()
        }
       -result
    } else {
        throw NumberFormatException()
    }
}

fun Long.toBase64(): String {
    val buf = StringBuffer()
    var i = -this
    while (i <= -64) {
        buf.append(EternalJukebox.BASE_64_URL[-(i % 64).toInt()])
        i /= 64
    }
    buf.append(EternalJukebox.BASE_64_URL[-i.toInt()])
    return buf.reverse().toString()
}
