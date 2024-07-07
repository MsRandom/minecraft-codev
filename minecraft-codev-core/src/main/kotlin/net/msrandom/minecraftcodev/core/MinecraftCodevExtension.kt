package net.msrandom.minecraftcodev.core

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionList
import net.msrandom.minecraftcodev.core.resolve.getClientDependencies
import net.msrandom.minecraftcodev.core.resolve.setupCommon
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

@Suppress("unused")
abstract class MinecraftCodevExtension(private val project: Project) : ExtensionAware {
    private var versionMetadataUrl: Property<String> =
        project.objects.property(String::class.java).apply {
            convention("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")
        }

    private var _versionList: MinecraftVersionList? = null

    suspend fun getVersionList() =
        _versionList ?: run {
            MinecraftVersionList.load(project, versionMetadataUrl.get()).also {
                _versionList = it
            }
        }

    private fun versionListResolver() =
        lazy {
        }

    fun versionMetadataUrl(url: String) {
        versionMetadataUrl.set(url)

        _versionList = null
    }

    fun versionMetadataUrl(url: Provider<String>) {
        versionMetadataUrl.set(url)

        _versionList = null
    }

    fun commonDependencies(version: String): Provider<List<Dependency>> =
        project.provider {
            runBlocking {
                setupCommon(project, getVersionList().version(version)).map(project.dependencies::create)
            }
        }

    fun clientDependencies(version: String): Provider<List<Dependency>> =
        project.provider {
            runBlocking {
                getClientDependencies(project, getVersionList().version(version))
            }
        }
}
