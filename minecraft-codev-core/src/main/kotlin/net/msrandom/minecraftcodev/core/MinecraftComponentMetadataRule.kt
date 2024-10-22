package net.msrandom.minecraftcodev.core

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionList
import net.msrandom.minecraftcodev.core.resolve.getClientDependencies
import net.msrandom.minecraftcodev.core.resolve.setupCommon
import net.msrandom.minecraftcodev.core.utils.toPath
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.file.Directory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import java.nio.file.Path
import javax.inject.Inject

const val VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

// TODO: Caching
suspend fun getVersionList(
    cacheDirectory: Path,
    url: String = VERSION_MANIFEST_URL,
    isOffline: Boolean,
) = MinecraftVersionList.load(cacheDirectory, url, isOffline)

abstract class MinecraftComponentMetadataRule<T : Any> @Inject constructor(
    private val cacheDirectory: Provider<Directory>,
    private val version: Provider<String>,
    private val versionManifestUrl: Provider<String>,
    private val isOffline: Provider<Boolean>,
    private val client: Boolean,
    private val variantName: String,
    private val attribute: Attribute<T>,
    private val attributeValue: T,
) : ComponentMetadataRule {
    abstract val objectFactory: ObjectFactory
        @Inject get

    override fun execute(context: ComponentMetadataContext) {
        context.details.addVariant(variantName) {
            it.attributes { attributes ->
                attributes
                    .attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category::class.java, Category.REGULAR_PLATFORM))
                    .attribute(attribute, attributeValue)
            }

            it.withDependencies {
                runBlocking {
                    val versionMetadata = getVersionList(cacheDirectory.get().toPath(), versionManifestUrl.get(), isOffline.get()).version(version.get())

                    if (client) {
                        setupCommon(cacheDirectory.get().toPath(), versionMetadata, isOffline.get())
                    } else {
                        getClientDependencies(cacheDirectory.get().toPath(), versionMetadata, isOffline.get())
                    }
                }
            }
        }
    }
}
