package net.msrandom.minecraftcodev.forge

import kotlinx.coroutines.runBlocking
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
import org.gradle.api.provider.Provider
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

@CacheableRule
abstract class MinecraftForgeComponentMetadataRule<T : Any> @Inject constructor(
    private val cacheDirectory: File,
    private val version: Provider<String>,
    private val versionManifestUrl: Provider<String>,
    private val isOffline: Provider<Boolean>,
    private val userdevGroup: String,
    private val userdevName: String,
    private val userdevVersion: Provider<String>,
    private val userdevClassifier: Provider<String>,
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
            val userdevJar by lazy {
                val userdevVersion = userdevVersion.get()
                val userdevClassifier = userdevClassifier.get()
                val path = "${userdevGroup.replace('.', '/')}/$userdevName/$userdevVersion/$userdevName-$userdevVersion-$userdevClassifier.jar"

                lateinit var userdev: UserdevConfig

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

                userdev
            }

            variant.attributes {
                it
                    .attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category::class.java, Category.REGULAR_PLATFORM))
                    .attribute(attribute, attributeValue)
            }

            variant.withDependencies {
                runBlocking {
                    val versionMetadata = getVersionList(cacheDirectory.toPath(), versionManifestUrl.get(), isOffline.get())
                        .version(version.get())

                    (getAllDependencies(versionMetadata) + userdevJar.libraries).forEach(it::add)
                }
            }

            variant.withDependencyConstraints {
                userdevJar.modules.forEach(it::add)
            }
        }
    }
}
