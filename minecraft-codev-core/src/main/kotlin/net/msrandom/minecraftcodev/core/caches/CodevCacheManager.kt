package net.msrandom.minecraftcodev.core.caches

import org.gradle.api.internal.artifacts.ivyservice.CacheLayout
import org.gradle.api.internal.filestore.DefaultArtifactIdentifierFileStore
import org.gradle.cache.CleanableStore
import org.gradle.cache.internal.*
import org.gradle.internal.file.FileAccessTimeJournal
import org.gradle.internal.resource.cached.DefaultExternalResourceFileStore
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
open class CodevCacheManager @Inject constructor(val rootPath: Path, fileAccessTimeJournal: FileAccessTimeJournal, usedGradleVersions: UsedGradleVersions) : CleanableStore {
    val externalResourcesStoreDirectory: Path = rootPath.resolve(CacheLayout.RESOURCES.key)
    val fileStoreDirectory: Path = rootPath.resolve(CacheLayout.FILE_STORE.key)
    val metaDataDirectory: Path = rootPath.resolve(CacheLayout.META_DATA.key)

    private val memoryCache = ConcurrentHashMap<Path, CachedPath<*, *>>()

    private val gcProperties = rootPath.resolve("gc.properties")
    private val lock = rootPath.resolve("${rootPath.name}.lock")

    private val cleanupAction = run {
        val maxAgeInDays = System.getProperty("org.gradle.internal.cleanup.external.max.age")?.toLongOrNull() ?: LeastRecentlyUsedCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_EXTERNAL_CACHE_ENTRIES

        CompositeCleanupAction.builder()
            .add(UnusedVersionsCacheCleanup.create(ROOT_NAME, CacheLayout.ROOT.versionMapping, usedGradleVersions))
            .add(
                externalResourcesStoreDirectory.toFile(),
                UnusedVersionsCacheCleanup.create(CacheLayout.RESOURCES.getName(), CacheLayout.RESOURCES.versionMapping, usedGradleVersions),
                LeastRecentlyUsedCacheCleanup(SingleDepthFilesFinder(DefaultExternalResourceFileStore.FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP), fileAccessTimeJournal, maxAgeInDays)
            )
            .add(
                fileStoreDirectory.toFile(),
                UnusedVersionsCacheCleanup.create(CacheLayout.FILE_STORE.getName(), CacheLayout.FILE_STORE.versionMapping, usedGradleVersions),
                LeastRecentlyUsedCacheCleanup(SingleDepthFilesFinder(DefaultArtifactIdentifierFileStore.FILE_TREE_DEPTH_TO_TRACK_AND_CLEANUP), fileAccessTimeJournal, maxAgeInDays)
            )
            .add(
                metaDataDirectory.toFile(),
                UnusedVersionsCacheCleanup.create(CacheLayout.META_DATA.getName(), CacheLayout.META_DATA.versionMapping, usedGradleVersions)
            )
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    fun <K, V> getMetadataCache(path: Path, keySerializer: () -> Serializer<K>, valueSerializer: () -> Serializer<V>) = memoryCache.computeIfAbsent(path) {
        CachedPath(keySerializer(), valueSerializer(), rootPath.resolve(CacheLayout.META_DATA.key).resolve(it))
    } as CachedPath<K, V>

    override fun getDisplayName() = "Minecraft Codev Caches $rootPath"
    override fun getBaseDir(): File = rootPath.toFile()
    override fun getReservedCacheFiles() = listOf(gcProperties.toFile(), lock.toFile())

    override fun toString() = displayName

    companion object {
        const val ROOT_NAME = "minecraft-codev"
    }

    @ThreadSafe
    inner class CachedPath<K, V>(private val keySerializer: Serializer<K>, private val valueSerializer: Serializer<V>, private val path: Path) {
        private val lock = ReentrantLock()
        private val fileCache = ConcurrentHashMap<Path, CachedFile>()

        val asFile = CachedFile(path)

        fun resolve(path: Path) = this.path.resolve(path).let { fullPath ->
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

            operator fun set(key: K, value: V) = lock.withLock {
                if (memoryCache.containsKey(key)) {
                    throw UnsupportedOperationException("$file: Modifying existing cache elements is not yet supported.")
                }

                val append = if (memoryCache.isEmpty()) {
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
