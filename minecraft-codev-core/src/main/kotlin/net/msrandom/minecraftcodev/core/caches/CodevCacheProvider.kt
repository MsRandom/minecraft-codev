package net.msrandom.minecraftcodev.core.caches

import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.artifacts.ivyservice.CacheLayout
import org.gradle.api.invocation.Gradle
import org.gradle.cache.scopes.GlobalScopedCacheBuilderFactory
import org.gradle.configurationcache.extensions.serviceOf
import java.util.concurrent.ConcurrentHashMap

fun CodevCacheProvider(gradle: Gradle) =
    object : CodevCacheProvider {
        private val root = (gradle as GradleInternal).serviceOf<GlobalScopedCacheBuilderFactory>()
        private val cache = ConcurrentHashMap<String, CodevCacheManager>()

        override fun manager(name: String) =
            cache.computeIfAbsent(name) {
                val path = root.baseDirForCrossVersionCache("${CodevCacheManager.ROOT_NAME}/$name-${CacheLayout.ROOT.version}").toPath()

                gradle as GradleInternal
                CodevCacheManager(path, gradle.serviceOf(), gradle.serviceOf())
            }
    }

interface CodevCacheProvider {
    fun manager(name: String): CodevCacheManager
}
