package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.invocation.Gradle
import org.gradle.internal.operations.BuildOperationContext
import java.util.concurrent.ConcurrentHashMap

private val cacheProviders = ConcurrentHashMap<Gradle, CodevCacheProvider>()

fun getCacheProvider(gradle: Gradle) = cacheProviders.computeIfAbsent(gradle, ::CodevCacheProvider)

fun osVersion(): String {
    val version = SystemUtils.OS_VERSION
    val versionEnd = version.indexOf('-')
    return if (versionEnd < 0) version else version.substring(0, versionEnd)
}

fun <R> BuildOperationContext.callWithStatus(action: () -> R): R {
    setStatus("EXECUTING")

    val result = try {
        action()
    } catch (failure: Throwable) {
        setStatus("FAILED")
        failed(failure)
        throw failure
    }

    setStatus("DONE")

    return result
}
