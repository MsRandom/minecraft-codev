package net.msrandom.minecraftcodev.core

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

    private var _versionList = versionListResolver()

    val versionList
        get() = _versionList.value

    private fun versionListResolver() =
        lazy {
            MinecraftVersionList.load(project, versionMetadataUrl.get())
        }

    fun versionMetadataUrl(url: String) {
        versionMetadataUrl.set(url)

        _versionList = versionListResolver()
    }

    fun versionMetadataUrl(url: Provider<String>) {
        versionMetadataUrl.set(url)

        _versionList = versionListResolver()
    }

    fun commonDependencies(version: String): Provider<List<Dependency>> =
        project.provider {
            setupCommon(project, versionList.version(version)).map(project.dependencies::create)
        }

    fun clientDependencies(version: String): Provider<List<Dependency>> =
        project.provider {
            getClientDependencies(project, versionList.version(version))
        }
}
