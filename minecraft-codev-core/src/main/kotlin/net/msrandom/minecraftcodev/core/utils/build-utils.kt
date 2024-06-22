package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
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

    val result =
        try {
            action()
        } catch (failure: Throwable) {
            setStatus("FAILED")
            failed(failure)
            throw failure
        }

    setStatus("DONE")

    return result
}

val ModuleComponentArtifactIdentifier.asSerializable: ModuleComponentArtifactIdentifier
    get() {
        val id = DefaultModuleComponentIdentifier(componentIdentifier.moduleIdentifier, componentIdentifier.version)
        return if (this is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, fileName)
        }
    }

fun <T : PluginAware> Plugin<T>.applyPlugin(
    target: T,
    action: Project.() -> Unit,
) = applyPlugin(target, {}, action)

fun <T : PluginAware> Plugin<T>.applyPlugin(
    target: T,
    gradleSetup: (Gradle) -> Unit,
    action: Project.() -> Unit,
) {
    target.plugins.apply(MinecraftCodevPlugin::class.java)

    return when (target) {
        is Gradle -> {
            gradleSetup(target)

            target.allprojects {
                target.plugins.apply(javaClass)
            }
        }

        is Settings ->
            target.gradle.apply {
                it.plugin(javaClass)
            }

        is Project -> {
            gradleSetup(target.gradle)
            target.action()
        }

        else -> Unit
    }
}
