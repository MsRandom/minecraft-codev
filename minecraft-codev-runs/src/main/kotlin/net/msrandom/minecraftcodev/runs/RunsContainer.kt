package net.msrandom.minecraftcodev.runs

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import javax.inject.Inject

sealed interface RunsContainer : NamedDomainObjectContainer<MinecraftRunConfiguration>, ExtensionAware {
    /**
     * Directory used for storing download asset objects & indexes
     */
    val assetsDirectory: DirectoryProperty

    /**
     * Legacy resources, for beta 1.6 and below
     */
    val resourcesDirectory: DirectoryProperty
}

abstract class RunsContainerImpl @Inject constructor(
    cacheDirectory: Provider<Directory>,
    objects: ObjectFactory,
) :
    RunsContainer, NamedDomainObjectContainer<MinecraftRunConfiguration> by objects.domainObjectContainer(MinecraftRunConfiguration::class.java) {
    init {
        apply {
            assetsDirectory.convention(cacheDirectory.map { it.dir("assets") })
            resourcesDirectory.convention(cacheDirectory.map { it.dir("resources") })
        }
    }
}
