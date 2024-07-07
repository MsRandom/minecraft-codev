package net.msrandom.minecraftcodev.forge

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.getClientDependencies
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider

open class PatchedMinecraftCodevExtension(private val project: Project) {
    fun dependencies(
        version: String,
        userdev: FileCollection,
    ): Provider<List<Dependency>> =
        project.provider {
            runBlocking {
                val versionList =
                    project
                        .extension<MinecraftCodevExtension>()
                        .getVersionList()

                getClientDependencies(project, versionList.version(version)) +
                    Userdev.fromFile(userdev.singleFile)!!.config.libraries.map(project.dependencies::create)
            }
        }
}
