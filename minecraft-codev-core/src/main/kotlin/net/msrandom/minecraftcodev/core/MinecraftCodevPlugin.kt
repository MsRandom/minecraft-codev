package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import net.msrandom.minecraftcodev.core.attributes.OperatingSystemDisambiguationRule
import net.msrandom.minecraftcodev.core.attributes.VersionPatternCompatibilityRule
import net.msrandom.minecraftcodev.core.dependency.*
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.named
import org.gradle.api.Plugin
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.OperatingSystemFamily

open class MinecraftCodevPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "minecraft",
        MinecraftIvyDependencyDescriptorFactory::class.java,
        MinecraftDependencyFactory::class.java,
        MinecraftComponentResolvers::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        handleCustomQueryResolvers()

        extensions.create("minecraft", MinecraftCodevExtension::class.java)

        dependencies.attributesSchema { schema ->
            schema.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE) {
                it.disambiguationRules.add(OperatingSystemDisambiguationRule::class.java)
            }

            schema.attribute(OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE) {
                it.compatibilityRules.add(VersionPatternCompatibilityRule::class.java)
            }

            schema.attribute(JAR_TRANSFORMED_ATTRIBUTE)
        }

        plugins.withType(JvmEcosystemPlugin::class.java) {
            dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
                it.attributes.attribute(JAR_TRANSFORMED_ATTRIBUTE, false)
            }
        }

        configurations.all { configuration ->
            configuration.attributes {
                it.attribute(JAR_TRANSFORMED_ATTRIBUTE, true)
                it.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, project.objects.named(OperatingSystem.current().familyName))
            }
        }

        @Suppress("UnstableApiUsage")
        dependencies.registerTransform(LaunchWrapperTransformer::class.java) {
            it.from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(JAR_TRANSFORMED_ATTRIBUTE, false)
            it.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(JAR_TRANSFORMED_ATTRIBUTE, true)
        }

        dependencies.components { components ->
            components.withModule("${MinecraftComponentResolvers.GROUP}:launchwrapper") { module ->
                module.allVariants { variant ->
                    variant.withDependencies { dependencies ->
                        dependencies.add("cpw.mods:grossjava9hacks:1.3.3")
                    }
                }
            }
        }
    }

    companion object {
        @JvmField
        val OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE: Attribute<String> = Attribute.of("net.msrandom.minecraftcodev.operatingSystemVersionPattern", String::class.java)

        @JvmField
        val JAR_TRANSFORMED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("net.msrandom.minecraftcodev.transformed", Boolean::class.javaObjectType)

        val json = Json {
            ignoreUnknownKeys = true
        }
    }
}
