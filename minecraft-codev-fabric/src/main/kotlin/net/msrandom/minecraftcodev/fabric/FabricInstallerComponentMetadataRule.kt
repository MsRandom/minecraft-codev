package net.msrandom.minecraftcodev.fabric

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.artifacts.VariantMetadata
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import java.net.URLClassLoader
import javax.inject.Inject

fun loadFabricInstaller(
    classpath: FileCollection,
    launchWrapper: Boolean,
): FabricInstaller? {
    val loader = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray())

    val suffix = if (launchWrapper) ".launchwrapper" else ""

    return loader.getResourceAsStream("fabric-installer$suffix.json")?.use {
        json.decodeFromStream<FabricInstaller>(it)
    }
}

@CacheableRule
abstract class FabricInstallerComponentMetadataRule<T : Any> @Inject constructor(
    private val sideAttribute: Attribute<T>,
    private val commonValue: T,
    private val clientValue: T,
    private val launchWrapper: Boolean = false,
) : ComponentMetadataRule {
    abstract val repositoryResourceAccessor: RepositoryResourceAccessor
        @Inject get

    override fun execute(context: ComponentMetadataContext) {
        val installerJson by lazy {
            val id = context.details.id
            val suffix = if (launchWrapper) "-launchwrapper" else ""
            val path = "${id.group.replace('.', '/')}/${id.name}/${id.version}/${id.name}-${id.version}$suffix.json"

            lateinit var installer: FabricInstaller

            repositoryResourceAccessor.withResource(path) {
                installer = json.decodeFromStream<FabricInstaller>(it)
            }

            installer
        }

        fun VariantMetadata.withSidedDependencies(
            sidedLibraries: FabricInstaller.FabricLibraries.() -> List<FabricInstaller.FabricLibrary>,
        ) = withDependencies { dependencies ->
            val libraries = installerJson.libraries

            dependencies.clear()
            libraries.sidedLibraries().map(FabricInstaller.FabricLibrary::name).forEach(dependencies::add)
        }

        context.details.withVariant("compile") {
            it.withSidedDependencies { common + development }

            it.attributes { attributes ->
                attributes.attribute(sideAttribute, commonValue)
            }
        }

        context.details.withVariant("runtime") {
            it.withSidedDependencies { common + server + development }

            it.attributes { attributes ->
                attributes.attribute(sideAttribute, commonValue)
            }
        }

        context.details.addVariant("clientCompile", "compile") {
            it.withSidedDependencies { common + client + development }

            it.attributes { attributes ->
                attributes.attribute(sideAttribute, clientValue)
            }
        }

        context.details.addVariant("clientRuntime", "compile") {
            it.withSidedDependencies { common + client + development }

            it.attributes { attributes ->
                attributes.attribute(sideAttribute, clientValue)
            }
        }
    }
}
