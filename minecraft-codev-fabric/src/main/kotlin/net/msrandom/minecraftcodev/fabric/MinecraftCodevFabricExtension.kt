package net.msrandom.minecraftcodev.fabric

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.resolve.getClientDependencies
import net.msrandom.minecraftcodev.core.resolve.setupCommon
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import java.net.URLClassLoader
import javax.inject.Inject

fun loadFabricInstaller(
    classpath: FileCollection,
    launchWrapper: Boolean,
): FabricInstaller? {
    val loader = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray())

    val suffix = if (launchWrapper) ".launchwrapper" else ""

    return loader.getResourceAsStream("fabric-installer$suffix.json")?.use {
        json.decodeFromStream<FabricInstaller>(it)
    }
}

open class MinecraftCodevFabricExtension
@Inject
constructor(private val project: Project) {
    private fun fabricInstallerDependencies(
        classpath: FileCollection,
        sidedLibraries: FabricInstaller.FabricLibraries.() -> List<FabricInstaller.FabricLibrary>,
        launchWrapper: Boolean,
    ): Provider<List<Dependency>> =
        project.provider {
            val installer =
                loadFabricInstaller(
                    classpath,
                    launchWrapper,
                ) ?: throw InvalidUserDataException("Could not find fabric-installer.json from fabric-loader in $classpath")

            installer.libraries
                .sidedLibraries()
                .map { project.dependencies.create(it.name) }
        }

    fun fabricCommonDependencies(
        version: String,
        classpath: FileCollection,
        launchWrapper: Boolean = false,
    ) = fabricInstallerDependencies(classpath, { server + common + development }, launchWrapper).map {
        runBlocking {
            val metadata = project.extension<MinecraftCodevExtension>().getVersionList().version(version)

            setupCommon(project, metadata, null).map(project.dependencies::create)
        }
    }

    fun fabricClientDependencies(
        version: String,
        classpath: FileCollection,
        launchWrapper: Boolean = false,
    ) = fabricInstallerDependencies(classpath, FabricInstaller.FabricLibraries::client, launchWrapper).map {
        runBlocking {
            val metadata = project.extension<MinecraftCodevExtension>().getVersionList().version(version)

            getClientDependencies(project, metadata)
        }
    }
}
