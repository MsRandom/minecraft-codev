package net.msrandom.minecraftcodev.core.utils

import org.gradle.api.file.*
import org.jetbrains.annotations.Blocking
import java.net.URI
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.streams.asSequence

private val fileSystemLocks = ConcurrentHashMap<Path, ReentrantLock>()

@Blocking
fun zipFileSystem(
    file: Path,
    create: Boolean = false,
): LockingFileSystem {
    val uri = URI.create("jar:${file.toUri()}")

    val lock =
        fileSystemLocks.computeIfAbsent(file.toAbsolutePath()) {
            ReentrantLock()
        }

    lock.lock()

    var owned = true

    val base =
        if (create) {
            FileSystems.newFileSystem(uri, mapOf("create" to true.toString()))
        } else {
            try {
                val fileSystem = FileSystems.getFileSystem(uri)

                owned = false

                fileSystem
            } catch (exception: FileSystemNotFoundException) {
                FileSystems.newFileSystem(uri, emptyMap<String, Any>())
            }
        }

    return LockingFileSystem(base, lock, owned)
}

fun <T> Path.walk(action: Sequence<Path>.() -> T) =
    Files.walk(this).use {
        it.asSequence().action()
    }

fun FileSystemLocation.toPath(): Path = asFile.toPath()
fun FileSystemLocationProperty<*>.getAsPath(): Path = asFile.get().toPath()

class LockingFileSystem(val base: FileSystem, private val lock: Lock, private val owned: Boolean) : AutoCloseable {
    operator fun component1() = base

    override fun close() {
        if (owned) {
            base.close()
        }

        lock.unlock()
    }
}
