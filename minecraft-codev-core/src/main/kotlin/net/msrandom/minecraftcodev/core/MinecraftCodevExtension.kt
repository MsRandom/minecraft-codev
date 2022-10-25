package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.gradle.api.plugins.ExtensionAware
import org.gradle.initialization.layout.GlobalCacheDir
import org.gradle.internal.impldep.org.apache.commons.lang.SystemUtils
import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

abstract class MinecraftCodevExtension @Inject constructor(
    project: Project,
    cacheDir: GlobalCacheDir
) : ExtensionAware {
    val cache: Path = cacheDir.dir.toPath().resolve("minecraft-codev")
    val assets: Path = cache.resolve("assets")
    val resources: Path = cache.resolve("resources")
    val logging: Path = cache.resolve("logging")

    init {
        project.configurations.create(MinecraftCodevPlugin.ACCESS_WIDENERS) {
            it.isCanBeConsumed = false
        }

        project.dependencies.attributesSchema { schema ->
            schema.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE) {
                it.disambiguationRules.add(OperatingSystemDisambiguationRule::class.java)
            }

            schema.attribute(MinecraftCodevPlugin.OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE) {
                it.compatibilityRules.add(VersionPatternCompatibilityRule::class.java)
            }

            schema.attribute(MinecraftCodevPlugin.ACCESS_WIDENED_ATTRIBUTE)
        }

        project.dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
            it.attributes.attribute(MinecraftCodevPlugin.ACCESS_WIDENED_ATTRIBUTE, false)
        }

        project.configurations.all { configuration ->
            configuration.attributes {
                it.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, project.named(OperatingSystem.current().familyName))
            }
        }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
        }

        fun osVersion(): String {
            val version = SystemUtils.OS_VERSION
            val versionEnd = version.indexOf('-')
            return if (versionEnd < 0) version else version.substring(0, versionEnd)
        }

        internal fun downloadToFile(path: Path, url: () -> URL): InputStream {
            val input = if (path.exists()) {
                path.inputStream()
            } else {
                val stream = url().openStream().buffered()
                try {
                    stream.mark(Int.MAX_VALUE)
                    path.parent.createDirectories()
                    Files.copy(stream, path)
                    stream.reset()
                } catch (exception: IOException) {
                    stream.close()
                    throw exception
                }
                stream
            }

            return input
        }
    }

    class OperatingSystemDisambiguationRule : AttributeDisambiguationRule<OperatingSystemFamily?> {
        override fun execute(details: MultipleCandidatesDetails<OperatingSystemFamily?>) {
            val consumerValue = details.consumerValue?.name

            if (consumerValue == null && null in details.candidateValues) {
                return details.closestMatch(null)
            }

            val effectiveConsumer = consumerValue ?: DefaultNativePlatform.host().operatingSystem.toFamilyName()

            val bestMatch = details.candidateValues.firstOrNull { it != null && it.name == effectiveConsumer }
            if (bestMatch != null) {
                return details.closestMatch(bestMatch)
            } else {
                if (null in details.candidateValues) {
                    return details.closestMatch(null)
                }
            }
        }
    }

    class VersionPatternCompatibilityRule : AttributeCompatibilityRule<String> {
        override fun execute(details: CompatibilityCheckDetails<String>) {
            val consumerValue = details.consumerValue
            val version = details.producerValue ?: osVersion()

            if (consumerValue == null) {
                details.compatible()
            } else {
                if (version matches Regex(consumerValue)) {
                    details.compatible()
                } else {
                    details.incompatible()
                }
            }
        }
    }
}
