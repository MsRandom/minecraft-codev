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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Supplier
import javax.annotation.concurrent.ThreadSafe
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlin.io.path.*

@ThreadSafe
open class CodevCacheManager @Inject constructor(val rootPath: Path, fileAccessTimeJournal: FileAccessTimeJournal, usedGradleVersions: UsedGradleVersions) : CleanableStore {
    val externalResourcesStoreDirectory: Path = rootPath.resolve(CacheLayout.RESOURCES.key)
    val fileStoreDirectory: Path = rootPath.resolve(CacheLayout.FILE_STORE.key)
    val metaDataDirectory: Path = rootPath.resolve(CacheLayout.META_DATA.key)

    private val memoryCache = ConcurrentHashMap<Path, CachedPath<*>>()

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
    fun <T> getMetadataCache(path: Path, defaultValue: T, serializer: () -> Serializer<T>) = memoryCache.computeIfAbsent(path) {
        CachedPath(serializer(), defaultValue, rootPath.resolve(CacheLayout.META_DATA.key).resolve(it))
    } as CachedPath<T>

    override fun getDisplayName() = "Minecraft Codev Caches $rootPath"
    override fun getBaseDir(): File = rootPath.toFile()
    override fun getReservedCacheFiles() = listOf(gcProperties.toFile(), lock.toFile())

    override fun toString() = displayName

    companion object {
        const val ROOT_NAME = "minecraft-codev"
    }

    @ThreadSafe
    inner class CachedPath<T>(private val serializer: Serializer<T>, private val defaultValue: T, private val path: Path) {
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

            private val _value = ValidatedLazy<T> {
                lock.withLock {
                    if (this.file.exists()) {
                        InputStreamBackedDecoder(this.file.inputStream()).use(serializer::read)
                    } else {
                        defaultValue
                    }
                }
            }

            val value get() = lock.withLock(_value::get)

            fun update(value: T) = lock.withLock {
                _value.invalidate()
                file.parent?.createDirectories()
                OutputStreamBackedEncoder(file.outputStream()).use { serializer.write(it, value) }
            }
        }
    }

    private class ValidatedLazy<T>(private val factory: () -> T) : () -> T, Supplier<T> {
        private var value: T? = null
        private var initialized = false

        override fun invoke() = get()

        @Suppress("UNCHECKED_CAST")
        override fun get() = if (initialized) value as T else factory().also { value = it }

        fun invalidate() {
            value = null
            initialized = false
        }
    }
}
