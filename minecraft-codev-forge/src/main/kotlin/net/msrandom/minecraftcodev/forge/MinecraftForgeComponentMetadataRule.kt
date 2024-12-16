package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.getVersionList
import net.msrandom.minecraftcodev.core.resolve.getAllDependencies
import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import java.io.File
import java.io.Serializable
import java.util.zip.ZipInputStream
import javax.inject.Inject

class UserdevPath(internal val group: String, internal val name: String, internal val version: String, internal val classifier: String) : Serializable

fun getUserdev(
    userdev: UserdevPath,
    repositoryResourceAccessor: RepositoryResourceAccessor,
): UserdevConfig? {
    val path = "${userdev.group.replace('.', '/')}/${userdev.name}/${userdev.version}/${userdev.name}-${userdev.version}-${userdev.classifier}.jar"

    var userdev: UserdevConfig? = null

    repositoryResourceAccessor.withResource(path) {
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

    return userdev
}

fun getClassifier(library: String) = library.split(':').getOrNull(3)?.split('@')?.get(0)

@CacheableRule
abstract class MinecraftForgeComponentMetadataRule<T : Any> @Inject constructor(
    private val cacheDirectory: File,
    private val version: String,
    private val versionManifestUrl: String,
    private val isOffline: Boolean,
    private val userdev: UserdevPath,
    private val variantName: String,
    private val attribute: Attribute<T>,
    private val attributeValue: T,
) : ComponentMetadataRule {
    abstract val objectFactory: ObjectFactory
        @Inject get

    abstract val repositoryResourceAccessor: RepositoryResourceAccessor
        @Inject get

    override fun execute(context: ComponentMetadataContext) {
        context.details.addVariant(variantName) { variant ->
            val userdevJar by lazy(LazyThreadSafetyMode.NONE) {
                getUserdev(userdev, repositoryResourceAccessor)!!
            }

            variant.attributes {
                it
                    .attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category::class.java, Category.REGULAR_PLATFORM))
                    .attribute(attribute, attributeValue)
            }

            variant.withDependencies {
                val versionMetadata = getVersionList(cacheDirectory.toPath(), versionManifestUrl, isOffline).version(version)

                val libraries = userdevJar.libraries.filterNot {
                    it == userdevJar.mcp
                }

                getAllDependencies(versionMetadata).forEach(it::add)

                for (library in libraries) {
                    it.add(library) {
                        val classifier = getClassifier(library)

                        if (classifier != null) {
                            it.attributes{ attributes ->
                                attributes.attribute(CLASSIFIER_ATTRIBUTE, classifier)
                            }
                        }
                    }
                }
            }

            variant.withDependencyConstraints {
                userdevJar.modules.forEach(it::add)
            }
        }
    }
}
