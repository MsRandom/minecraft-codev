package net.msrandom.minecraftcodev.core.utils

import java.net.URI
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.copyTo
import kotlin.streams.asSequence

private val fileSystemLocks = ConcurrentHashMap<URI, ReentrantLock>()

fun Path.createDeterministicCopy(prefix: String, suffix: String): Path {
    val path = Files.createTempFile(prefix, suffix)
    copyTo(path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

    return path
}

fun zipFileSystem(file: Path, create: Boolean = false): LockingFileSystem {
    val uri = URI.create("jar:${file.toUri()}")

    val lock = fileSystemLocks.computeIfAbsent(uri) {
        ReentrantLock()
    }

    while (lock.isLocked) {
        // Wait until lock unlocks
    }

    lock.lock()

    val base = if (create) {
        FileSystems.newFileSystem(uri, mapOf("create" to true.toString()))
    } else {
        try {
            FileSystems.getFileSystem(uri)
        } catch (exception: FileSystemNotFoundException) {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        }
    }

    return LockingFileSystem(base, lock)
}

fun <T> Path.walk(action: Sequence<Path>.() -> T) = Files.walk(this).use {
    it.asSequence().action()
}

class LockingFileSystem(val base: FileSystem, private val lock: Lock) : AutoCloseable {
    override fun close() {
        base.close()
        lock.unlock()
    }
}
