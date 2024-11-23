package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.model.ObjectFactory
import org.gradle.nativeplatform.OperatingSystemFamily
import java.io.File
import javax.inject.Inject

@CacheableRule
abstract class MinecraftDependenciesOperatingSystemMetadataRule @Inject constructor(
    private val cacheDirectory: File,
    private val versions: List<String>,
    private val versionManifestUrl: String,
    private val isOffline: Boolean,
) : ComponentMetadataRule {
    abstract val objectFactory: ObjectFactory
        @Inject get

    override fun execute(context: ComponentMetadataContext) {
        val versionList = getVersionList(cacheDirectory.toPath(), versionManifestUrl, isOffline)

        val id = context.details.id

        val libraries = versions.asSequence().flatMap {
            versionList.version(it).libraries
        }.filter {
            it.name.group == id.group && it.name.module == id.name && it.name.version == id.version
        }

        val classifierOperatingSystems = libraries.flatMap {
            buildList {
                if (it.name.classifier != null) {
                    val osName = it.rules.firstOrNull {
                        it.action == MinecraftVersionMetadata.RuleAction.Allow
                    }?.os?.name

                    if (osName != null) {
                        add(osName to it.downloads.artifact!!.path.substringAfterLast('/'))
                    }
                }

                addAll(it.natives.entries.map { (key, value) ->
                    key to it.downloads.classifiers.getValue(value).path.substringAfterLast('/')
                })
            }
        }

        for ((operatingSystem, file) in classifierOperatingSystems.distinctBy { (operatingSystem, _) -> operatingSystem }) {
            context.details.addVariant(operatingSystem, "runtime") { variant ->
                variant.attributes { attribute ->
                    attribute.attribute(
                        OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                        objectFactory.named(OperatingSystemFamily::class.java, operatingSystem),
                    )
                }

                variant.withFiles { files ->
                    files.addFile(file)
                }
            }
        }
    }
}
