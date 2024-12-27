package net.msrandom.minecraftcodev.core.task

import net.msrandom.minecraftcodev.core.VERSION_MANIFEST_URL
import net.msrandom.minecraftcodev.core.getVersionList
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.getGlobalCacheDirectoryProvider
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested

fun CachedMinecraftParameters.convention(project: Project) {
    versionManifestUrl.convention(VERSION_MANIFEST_URL)

    directory.set(getGlobalCacheDirectoryProvider(project))
    getIsOffline().set(project.provider { project.gradle.startParameter.isOffline })
}

fun CachedMinecraftParameters.versionList() =
    getVersionList(directory.getAsPath(), versionManifestUrl.get(), getIsOffline().get())

interface CachedMinecraftParameters {
    val versionManifestUrl: Property<String>
        @Input get

    val directory: DirectoryProperty
        @Internal get

    @Internal
    fun getIsOffline(): Property<Boolean>
}

abstract class CachedMinecraftTask : DefaultTask() {
    abstract val cacheParameters: CachedMinecraftParameters
        @Nested get

    init {
        cacheParameters.convention(project)
    }
}
