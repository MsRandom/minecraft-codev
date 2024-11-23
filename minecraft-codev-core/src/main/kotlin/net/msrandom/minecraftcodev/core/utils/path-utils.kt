package net.msrandom.minecraftcodev.core.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileSystemLocationProperty
import org.jetbrains.annotations.Blocking
import org.slf4j.LoggerFactory
import java.lang.Thread.sleep
import java.net.URI
import java.nio.file.*
import kotlin.streams.asSequence
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("path-utils")

@Blocking
fun zipFileSystem(
    file: Path,
    create: Boolean = false,
): FileSystem {
    val uri = URI.create("jar:${file.toUri()}")

    if (create) {
        return FileSystems.newFileSystem(uri, mapOf("create" to true.toString()))
    }

    while (true) {
        try {
            return FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        } catch (e: FileSystemAlreadyExistsException) {
            logger.info("Couldn't acquire access to $file file-system, waiting", e)

            sleep(1.seconds.inWholeMilliseconds)
        }
    }
}

fun <T> Path.walk(action: Sequence<Path>.() -> T) =
    Files.walk(this).use {
        it.asSequence().action()
    }

fun FileSystemLocation.toPath(): Path = asFile.toPath()
fun FileSystemLocationProperty<*>.getAsPath(): Path = asFile.get().toPath()
