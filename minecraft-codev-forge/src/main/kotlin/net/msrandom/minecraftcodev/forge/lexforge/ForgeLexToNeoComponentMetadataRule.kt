package net.msrandom.minecraftcodev.forge.lexforge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.getVersionList
import net.msrandom.minecraftcodev.core.resolve.getAllDependencies
import net.msrandom.minecraftcodev.core.utils.withCachedResource
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.forge.disableVariant
import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.model.ObjectFactory
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

internal class UserdevPath(internal val group: String, internal val name: String, internal val version: String, internal val classifier: String)

private fun getUserdev(
    cacheDirectory: File,
    userdev: UserdevPath,
    repositoryResourceAccessor: RepositoryResourceAccessor,
): Pair<UserdevConfig, String>? {
    val fileName = "${userdev.name}-${userdev.version}-${userdev.classifier}.jar"
    val path = "${userdev.group.replace('.', '/')}/${userdev.name}/${userdev.version}/$fileName"

    var userdev: UserdevConfig? = null

    repositoryResourceAccessor.withCachedResource(cacheDirectory, path) {
        val zipStream = ZipInputStream(it)

        while (true) {
            val entry = zipStream.nextEntry ?: break

            try {
                if (entry.name == "config.json") {
                    userdev = json.decodeFromStream<UserdevConfig>(zipStream)
                    break
                }
            } finally {
                zipStream.closeEntry()
            }
        }
    }

    if (userdev != null) {
        return userdev to fileName
    }

    return null
}

internal fun dependencyFileName(notation: String): String {
    val name = notation.substringAfter(':').replace(':', '-')

    return if ('@' in name) {
        name.replace('@', '.')
    } else {
        "$name.jar"
    }
}

// Partially copied from ModDevGradle, used to make forge's metadata compatible with neoforge's, thus not needing multiple pipelines
//  Also saves us from resolving as many files
@CacheableRule
abstract class ForgeLexToNeoComponentMetadataRule @Inject constructor(
    private val cacheDirectory: File,
    private val versionManifestUrl: String,
    private val isOffline: Boolean,
) : ComponentMetadataRule {
    abstract val objectFactory: ObjectFactory
        @Inject get

    abstract val repositoryResourceAccessor: RepositoryResourceAccessor
        @Inject get

    override fun execute(context: ComponentMetadataContext) {
        val id = context.details.id

        val userdevClassifiers = listOf("userdev", "userdev3")

        val (userdev, userdevJarName) = userdevClassifiers.firstNotNullOfOrNull { classifier ->
            getUserdev(
                cacheDirectory,
                UserdevPath(id.group, id.name, id.version, classifier),
                repositoryResourceAccessor
            )
        } ?: return

        context.details.addVariant("modDevBundle") { variantMetadata ->
            variantMetadata.withFiles { metadata -> metadata.addFile(userdevJarName) }

            variantMetadata.withDependencies {
                it.add(userdev.mcp)
            }

            variantMetadata.withCapabilities { capabilities ->
                capabilities.addCapability(id.group, "${id.name}-moddev-bundle", id.version)
            }
        }

        context.details.addVariant("modDevDependencies") { variant ->
            val (classifiers, libraries) = userdev.libraries.partition {
                it.startsWith("${id.group}:${id.name}:")
            }

            variant.attributes { attributes ->
                attributes.attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    objectFactory.named(Category::class.java, Category.LIBRARY),
                )

                attributes.attribute(
                    Bundling.BUNDLING_ATTRIBUTE,
                    objectFactory.named(Bundling::class.java, Bundling.EXTERNAL),
                )
            }

            variant.withCapabilities { capabilities ->
                capabilities.addCapability(id.group, "${id.name}-dependencies", id.version)
            }

            variant.withFiles {
                it.removeAllFiles()

                for (classifierDependency in classifiers) {
                    it.addFile(dependencyFileName(classifierDependency))
                }
            }

            variant.withDependencies { dependencies ->
                val minecraftVersion = userdev.mcp.substringAfterLast(':').substringBefore('-')

                val versionMetadata = getVersionList(cacheDirectory.toPath(), versionManifestUrl, isOffline).version(minecraftVersion)

                getAllDependencies(versionMetadata).forEach(dependencies::add)
                libraries.forEach(dependencies::add)
            }

            variant.withDependencyConstraints {
                userdev.modules.forEach(it::add)
            }
        }

        context.details.addVariant("universalJar") { variantMetadata ->
            variantMetadata.attributes { attributes ->
                attributes.attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    objectFactory.named(Category::class.java, Category.LIBRARY),
                )

                attributes.attribute(
                    Bundling.BUNDLING_ATTRIBUTE,
                    objectFactory.named(Bundling::class.java, Bundling.EXTERNAL),
                )

                attributes.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage::class.java, Usage.JAVA_RUNTIME))

                attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objectFactory.named(LibraryElements::class.java, LibraryElements.JAR),
                )
            }

            variantMetadata.withFiles {
                it.addFile(dependencyFileName(userdev.universal))
            }
        }

        // Use a fake capability to make it impossible for the implicit variants to be selected
        for (implicitVariantName in listOf("compile", "runtime")) {
            context.details.withVariant(implicitVariantName) { variant ->
                variant.withCapabilities {
                    it.disableVariant()
                }
            }
        }
    }
}
