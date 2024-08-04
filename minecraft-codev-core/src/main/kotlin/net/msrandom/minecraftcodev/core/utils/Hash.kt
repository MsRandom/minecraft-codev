package net.msrandom.minecraftcodev.core.utils

import com.google.common.hash.HashCode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.inputStream

private val hashCache = ConcurrentHashMap<Path, HashCode>()

suspend fun hashFile(file: Path): HashCode {
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

                    HashCode.fromBytes(sha1Hash.digest())
                }
            }

        hashCache[file] = hash

        hash
    }
}

suspend fun checkHash(
    file: Path,
    expectedHash: String,
) = hashFile(file) == HashCode.fromString(expectedHash)
