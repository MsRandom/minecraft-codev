package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionList
import net.msrandom.minecraftcodev.core.resolve.getAllDependencies
import net.msrandom.minecraftcodev.core.resolve.setupCommon
import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.artifacts.Dependency
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

const val VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

// TODO: Caching
fun getVersionList(
    cacheDirectory: Path,
    url: String = VERSION_MANIFEST_URL,
    isOffline: Boolean,
) = MinecraftVersionList.load(cacheDirectory, url, isOffline)

@CacheableRule
abstract class MinecraftComponentMetadataRule<T : Any> @Inject constructor(
    private val cacheDirectory: File,
    private val versions: List<String>,
    private val versionManifestUrl: String,
    private val isOffline: Boolean,

    private val commonCapability: String,
    private val clientCapability: String,
) : ComponentMetadataRule {
    private fun ComponentMetadataContext.addVariantDependencies(capabilityName: String, client: Boolean) {
        details.addVariant(capabilityName, Dependency.DEFAULT_CONFIGURATION) { variant ->
            variant.withCapabilities {
                for (capability in it.capabilities) {
                    it.removeCapability(capability.group, capability.name)
                }

                it.addCapability("net.msrandom", capabilityName, "0.0.0")
            }

            variant.withDependencies { dependencies ->
                val versionList = getVersionList(cacheDirectory.toPath(), versionManifestUrl, isOffline)

                val versionDependencies = versions.map {
                    val versionMetadata = versionList.version(versions[0])

                    val dependencies = if (client) {
                        getAllDependencies(versionMetadata)
                    } else {
                        setupCommon(cacheDirectory.toPath(), versionMetadata, isOffline)
                    }

                    dependencies.map(ModuleLibraryIdentifier::load)
                }

                val commonDependencies = versionDependencies.reduce { a, b ->
                    val moduleVersionsA = a.associate {
                        (it.group to it.module) to it.version
                    }

                    val moduleVersionsB = b.associate {
                        (it.group to it.module) to it.version
                    }

                    val commonModules = moduleVersionsA.keys intersect moduleVersionsB.keys

                    commonModules.map { module ->
                        val (group, name) = module
                        val version = minOf(moduleVersionsA.getValue(module), moduleVersionsB.getValue(module))

                        ModuleLibraryIdentifier(group, name, version, null)
                    }
                }

                for (dependency in commonDependencies) {
                    dependencies.add(dependency.toString())
                }
            }
        }
    }

    override fun execute(context: ComponentMetadataContext) {
        context.addVariantDependencies(commonCapability, false)
        context.addVariantDependencies(clientCapability, true)
    }
}
