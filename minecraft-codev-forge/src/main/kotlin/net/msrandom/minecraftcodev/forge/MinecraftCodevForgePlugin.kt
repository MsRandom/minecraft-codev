package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.core.applyPlugin
import net.msrandom.minecraftcodev.core.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.forge.resolve.PatchedMinecraftComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.plugins.PluginAware
import java.io.File
import java.nio.file.FileSystem
import kotlin.io.path.exists
import kotlin.io.path.inputStream

open class MinecraftCodevForgePlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        gradle.registerCustomDependency(
            "patched",
            PatchedMinecraftIvyDependencyDescriptorFactory::class.java,
            PatchedMinecraftComponentResolvers::class.java
        )
    }

    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        applyPlugin(target, ::applyGradle) {
            createSourceSetConfigurations(PATCHES_CONFIGURATION)

            dependencies.attributesSchema {
                it.attribute(FORGE_TRANSFORMED_ATTRIBUTE)
            }

            plugins.withType(JvmEcosystemPlugin::class.java) {
                dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
                    it.attributes.attribute(FORGE_TRANSFORMED_ATTRIBUTE, false)
                }
            }

            configurations.all { configuration ->
                configuration.attributes {
                    it.attribute(FORGE_TRANSFORMED_ATTRIBUTE, true)
                }
            }

            @Suppress("UnstableApiUsage")
            dependencies.registerTransform(ForgeJarTransformer::class.java) {
                it.from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(FORGE_TRANSFORMED_ATTRIBUTE, false)
                it.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(FORGE_TRANSFORMED_ATTRIBUTE, true)
            }

            setupForgeRemapperIntegration()
            setupForgeRunsIntegration()
        }
    }

    companion object {
        const val SRG_MAPPINGS_NAMESPACE = "srg"

        val FORGE_TRANSFORMED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("net.msrandom.minecraftcodev.transformed", Boolean::class.javaObjectType)

        const val PATCHES_CONFIGURATION = "patches"

        internal fun userdevConfig(file: File, action: FileSystem.(config: UserdevConfig) -> Unit) = zipFileSystem(file.toPath()).use { fs ->
            val configPath = fs.getPath("config.json")
            if (configPath.exists()) {
                fs.action(configPath.inputStream().use(json::decodeFromStream))
                true
            } else {
                false
            }
        }
    }
}
