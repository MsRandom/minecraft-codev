package net.msrandom.minecraftcodev.core.caches

import org.gradle.api.internal.artifacts.ivyservice.CacheLayout
import org.gradle.cache.CleanableStore
import org.gradle.cache.internal.*
import org.gradle.internal.file.FileAccessTimeJournal
import org.gradle.internal.serialize.InputStreamBackedDecoder
import org.gradle.internal.serialize.OutputStreamBackedEncoder
import org.gradle.internal.serialize.Serializer
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.ThreadSafe
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlin.io.path.*

@ThreadSafe
open class CodevCacheManager
@Inject
constructor(
    val rootPath: Path,
    fileAccessTimeJournal: FileAccessTimeJournal,
    usedGradleVersions: UsedGradleVersions,
) : CleanableStore {
    val fileStoreDirectory: Path = rootPath.resolve(CacheLayout.FILE_STORE.key)
    val metaDataDirectory: Path = rootPath.resolve(CacheLayout.META_DATA.key)

    private val memoryCache = ConcurrentHashMap<Path, CachedPath<*, *>>()

    private val gcProperties = rootPath.resolve("gc.properties")
    private val lock = rootPath.resolve("${rootPath.name}.lock")

    @Suppress("UNCHECKED_CAST")
    fun <K, V> getMetadataCache(
        path: Path,
        keySerializer: () -> Serializer<K>,
        valueSerializer: () -> Serializer<V>,
    ) = memoryCache.computeIfAbsent(path) {
        CachedPath(keySerializer(), valueSerializer(), metaDataDirectory.resolve(it))
    } as CachedPath<K, V>

    override fun getDisplayName() = "Minecraft Codev Caches $rootPath"

    override fun getBaseDir(): File = rootPath.toFile()

    override fun getReservedCacheFiles() = listOf(gcProperties.toFile(), lock.toFile())

    override fun toString() = displayName

    companion object {
        const val ROOT_NAME = "minecraft-codev"
    }

    @ThreadSafe
    inner class CachedPath<K, V>(
        private val keySerializer: Serializer<K>,
        private val valueSerializer: Serializer<V>,
        private val path: Path,
    ) {
        private val lock = ReentrantLock()
        private val fileCache = ConcurrentHashMap<Path, CachedFile>()

        val asFile = CachedFile(path)

        fun resolve(path: Path) =
            this.path.resolve(path).let { fullPath ->
                if (this.path == fullPath) {
                    asFile
                } else {
                    fileCache.computeIfAbsent(path) { CachedFile(fullPath) }
                }
            }

        @ThreadSafe
        inner class CachedFile(file: Path) {
            private val file = Paths.get("$file.bin")

            private val memoryCache by lazy {
                lock.withLock {
                    if (this.file.exists()) {
                        val input = this.file.inputStream()
                        val decoder = InputStreamBackedDecoder(input)
                        val map = ConcurrentHashMap<K, V>()

                        while (input.available() > 0) {
                            map[keySerializer.read(decoder)] = valueSerializer.read(decoder)
                        }

                        map
                    } else {
                        ConcurrentHashMap()
                    }
                }
            }

            operator fun get(key: K) = memoryCache[key]

            operator fun set(
                key: K,
                value: V,
            ) = lock.withLock {
                if (memoryCache.containsKey(key)) {
                    file.parent?.createDirectories()

                    memoryCache[key] = value

                    OutputStreamBackedEncoder(file.outputStream()).use {
                        for ((existingKey, existingValue) in memoryCache) {
                            keySerializer.write(it, existingKey)
                            valueSerializer.write(it, existingValue)
                        }
                    }
                } else {
                    val append =
                        if (memoryCache.isEmpty()) {
                            file.parent?.createDirectories()
                            emptyArray()
                        } else {
                            arrayOf(StandardOpenOption.APPEND)
                        }

                    OutputStreamBackedEncoder(file.outputStream(*append)).use {
                        keySerializer.write(it, key)
                        valueSerializer.write(it, value)
                    }

                    memoryCache[key] = value
                }
            }
        }
    }
}
