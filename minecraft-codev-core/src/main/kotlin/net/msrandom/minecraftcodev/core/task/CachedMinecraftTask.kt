package net.msrandom.minecraftcodev.core.task

import net.msrandom.minecraftcodev.core.VERSION_MANIFEST_URL
import net.msrandom.minecraftcodev.core.getVersionList
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.getCacheDirectoryProvider
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import javax.inject.Inject

abstract class CachedMinecraftParameters {
    abstract val objectFactory: ObjectFactory
        @Inject get

    abstract val versionManifestUrl: Property<String>
        @Input get

    val directory: DirectoryProperty = objectFactory.directoryProperty()
        @Internal get

    val isOffline: Property<Boolean> = objectFactory.property(Boolean::class.javaObjectType)
        @JvmName("getIsOffline")
        @Internal
        get

    fun convention(project: Project) {
        versionManifestUrl.convention(VERSION_MANIFEST_URL)

        directory.convention(getCacheDirectoryProvider(project))
        isOffline.convention(project.provider { project.gradle.startParameter.isOffline })
    }

    fun versionList() = getVersionList(directory.getAsPath(), versionManifestUrl.get(), isOffline.get())
}

abstract class CachedMinecraftTask : DefaultTask() {
    abstract val cacheParameters: CachedMinecraftParameters
        @Nested get

    init {
        cacheParameters.convention(project)
    }
}
