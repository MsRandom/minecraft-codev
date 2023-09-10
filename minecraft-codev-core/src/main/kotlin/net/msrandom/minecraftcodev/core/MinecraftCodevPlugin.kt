package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import net.msrandom.minecraftcodev.core.attributes.OperatingSystemDisambiguationRule
import net.msrandom.minecraftcodev.core.attributes.VersionPatternCompatibilityRule
import net.msrandom.minecraftcodev.core.dependency.MinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.core.dependency.handleCustomQueryResolvers
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.utils.CODEV_MAPPING_NAMESPACE_ATTRIBUTE
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.named
import org.gradle.api.Plugin
import org.gradle.api.attributes.Attribute
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.OperatingSystemFamily
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream

open class MinecraftCodevPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "minecraft",
        MinecraftIvyDependencyDescriptorFactory::class.java,
        MinecraftComponentResolvers::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        handleCustomQueryResolvers()

        val codev = extensions.create("minecraft", MinecraftCodevExtension::class.java)

        codev.modInfoDetectionRules.add {
            if (it.getPath("pack.mcmeta").exists()) {
                ModPlatformInfo("vanilla", 1)
            } else {
                null
            }
        }

        codev.modInfoDetectionRules.add {
            val path = it.getPath("META-INF", "MANIFEST.MF")

            if (path.exists()) {
                val attributes = path.inputStream().use(::Manifest).mainAttributes

                attributes.getValue(CODEV_MAPPING_NAMESPACE_ATTRIBUTE)?.let { namespace -> ModMappingNamespaceInfo(namespace, 4) }
            } else {
                null
            }
        }

        dependencies.attributesSchema { schema ->
            schema.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE) {
                it.disambiguationRules.add(OperatingSystemDisambiguationRule::class.java)
            }

            schema.attribute(OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE) {
                it.compatibilityRules.add(VersionPatternCompatibilityRule::class.java)
            }
        }

        project.configurations.all { configuration ->
            configuration.incoming.attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, project.objects.named(OperatingSystem.current().familyName))
        }
    }

    companion object {
        @JvmField
        val OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE: Attribute<String> = Attribute.of("net.msrandom.minecraftcodev.operatingSystemVersionPattern", String::class.java)

        val json = Json {
            ignoreUnknownKeys = true
        }
    }
}
