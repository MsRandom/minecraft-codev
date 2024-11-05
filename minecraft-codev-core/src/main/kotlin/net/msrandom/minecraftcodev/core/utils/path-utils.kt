package net.msrandom.minecraftcodev.core.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileSystemLocationProperty
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.streams.asSequence
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("path-utils")

private val mutexes = ConcurrentHashMap<Any?, Mutex>()

private fun objectMutex(obj: Any?) = mutexes.computeIfAbsent(obj) { Mutex() }

suspend fun <K : Any, V : Any> ConcurrentHashMap<K, V>.computeSuspendIfAbsent(key: K, compute: suspend (K) -> V): V {
    get(key)?.let {
        return it
    }

    return objectMutex(this).withLock {
        get(key)?.let {
            return it
        }

        val value = compute(key)

        put(key, value)

        value
    }
}

suspend fun zipFileSystem(
    file: Path,
    create: Boolean = false,
): FileSystem {
    val uri = URI.create("jar:${file.toUri()}")

    return withContext(Dispatchers.IO) {
        if (create) {
            FileSystems.newFileSystem(uri, mapOf("create" to true.toString()))
        } else {
            while (true) {
                try {
                    return@withContext FileSystems.newFileSystem(uri, emptyMap<String, Any>())
                } catch (e: FileSystemAlreadyExistsException) {
                    logger.info("Couldn't acquire access to $file file-system, waiting", e)

                    delay(1.seconds)
                }
            }

            // Unreachable
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }
    }
}

fun <T> Path.walk(action: Sequence<Path>.() -> T) =
    Files.walk(this).use {
        it.asSequence().action()
    }

fun FileSystemLocation.toPath(): Path = asFile.toPath()
fun FileSystemLocationProperty<*>.getAsPath(): Path = asFile.get().toPath()
