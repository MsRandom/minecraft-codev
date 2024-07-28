package net.msrandom.minecraftcodev.core.utils

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.inputStream

private val hashCache = ConcurrentHashMap<Path, ByteArray>()
private val stringHashCache = ConcurrentHashMap<Path, String>()

private fun sha1ToBytes(sha1: String) =
    sha1
        .windowed(2, 2, true)
        .map { it.toUByte(16).toByte() }
        .toByteArray()

fun hashToString(hash: ByteArray) = hash.joinToString("") { it.toString(16) }

suspend fun stringHashFile(file: Path): String {
    stringHashCache[file]?.let {
        return it
    }

    val hash = hashToString(hashFile(file))

    stringHashCache[file] = hash

    return hash
}

suspend fun hashFile(file: Path): ByteArray {
    hashCache[file]?.let {
        return it
    }

    return coroutineScope {
        val hash =
            runBlocking {
                file.inputStream().use { stream ->
                    val sha1Hash = MessageDigest.getInstance("SHA-1")

                    val buffer = ByteArray(8192)

                    var read: Int

                    while (stream.read(buffer).also { read = it } > 0) {
                        sha1Hash.update(buffer, 0, read)
                    }

                    sha1Hash.digest()
                }
            }

        hashCache[file] = hash

        hash
    }
}

suspend fun checkHash(
    file: Path,
    expectedHash: String,
) = hashFile(file) contentEquals sha1ToBytes(expectedHash)
