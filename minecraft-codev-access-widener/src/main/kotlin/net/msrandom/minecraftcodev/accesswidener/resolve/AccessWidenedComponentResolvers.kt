package net.msrandom.minecraftcodev.accesswidener.resolve

import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerReader
import net.msrandom.minecraftcodev.accesswidener.JarAccessWidener
import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedDependencyMetadata
import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedDependencyMetadataWrapper
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.minecraft.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.minecraft.SourcesArtifactComponentMetadata
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.AttributeValue
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.hash.HashCode
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class AccessWidenedComponentResolvers @Inject constructor(
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val buildOperationExecutor: BuildOperationExecutor,

    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("access-widened")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { AccessWidenedArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun wrapMetadata(metadata: ComponentResolveMetadata, identifier: AccessWidenedComponentIdentifier) = metadata.copy(
        objects,
        identifier,
        {
            map { artifact ->
                if (artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                    AccessWidenedComponentArtifactMetadata(artifact, identifier, null)
                } else {
                    artifact
                }
            }
        },
        {
            val namespace = attributes.findEntry(MappingsNamespace.attribute.name).takeIf(AttributeValue<*>::isPresent)?.get() as? String
            val category = attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)

            var sources = false

            val shouldWrap = if (category.isPresent) {
                if (category.get() == Category.LIBRARY) {
                    val libraryElements = attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)
                    libraryElements.isPresent && libraryElements.get() == LibraryElements.JAR
                } else if (category.get() == Category.DOCUMENTATION) {
                    val docsType = attributes.findEntry(DocsType.DOCS_TYPE_ATTRIBUTE.name)
                    sources = docsType.isPresent && docsType.get() == DocsType.SOURCES

                    sources
                } else {
                    false
                }
            } else {
                false
            }

            if (shouldWrap) {
                copy(
                    objects,
                    { oldName ->
                        object : DisplayName {
                            override fun getDisplayName() = "access widened ${oldName.displayName}"
                            override fun getCapitalizedDisplayName() = "Access Widened ${oldName.capitalizedDisplayName}"
                        }
                    },
                    attributes,
                    {
                        map { dependency ->
                            if (dependency.selector.attributes.getAttribute(MappingsNamespace.attribute) != null) {
                                // Maybe pass the namespace to use instead of the metadata one?
                                AccessWidenedDependencyMetadataWrapper(
                                    dependency,
                                    identifier.accessWidenersConfiguration
                                )
                            } else {
                                dependency
                            }
                        }
                    },
                    {
                        if (name.type == ArtifactTypeDefinition.JAR_TYPE) {
                            if (sources) {
                                val library = artifacts.first { it.name.type == ArtifactTypeDefinition.JAR_TYPE }
                                SourcesArtifactComponentMetadata(library as ModuleComponentArtifactMetadata, id as ModuleComponentArtifactIdentifier)
                            } else {
                                AccessWidenedComponentArtifactMetadata(this as ModuleComponentArtifactMetadata, identifier, namespace)
                            }
                        } else {
                            PassthroughAccessWidenedArtifactMetadata(this)
                        }
                    }
                )
            } else {
                this
            }
        }
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is AccessWidenedDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.metadata
                val accessWidenersConfiguration = dependency.relatedConfiguration ?: MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION

                if (result.id is ModuleComponentIdentifier) {
                    val id = AccessWidenedComponentIdentifier(result.id as ModuleComponentIdentifier, accessWidenersConfiguration)

                    if (metadata == null) {
                        result.resolved(id, result.moduleVersionId)
                    } else {
                        result.resolved(wrapMetadata(metadata, id))
                    }
                }
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is AccessWidenedComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(wrapMetadata(result.metadata, identifier))
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is AccessWidenedComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is AccessWidenedComponentIdentifier) {
        MetadataSourcedComponentArtifacts().getArtifactsFor(
            component, configuration, artifactResolver, hashMapOf(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory
        )
        // resolvers.artifactSelector.resolveArtifacts(CodevGradleLinkageLoader.getDelegate(component), configuration, exclusions, overriddenAttributes)
    } else {
        null
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        if (artifact is AccessWidenedComponentArtifactMetadata) {
            val id = artifact.componentId
            resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, result)

            if (result.isSuccessful) {
                val accessWideners = project.configurations.getByName(id.accessWidenersConfiguration)

                val messageDigest = MessageDigest.getInstance("SHA1")

                val accessWidener = AccessWidener().also { visitor ->
                    val reader = AccessWidenerReader(visitor)

                    project.visitConfigurationFiles(resolvers, accessWideners) { file ->
                        DigestInputStream(file.inputStream(), messageDigest).bufferedReader().use {
                            if (artifact.namespace == null) {
                                reader.read(it)
                            } else {
                                reader.read(it, artifact.namespace)
                            }
                        }
                    }
                }

                val hash = HashCode.fromBytes(messageDigest.digest())

                val urlId = AccessWidenedArtifactIdentifier(
                    artifact.id.asSerializable,
                    hash,
                    checksumService.sha1(result.result)
                )

                getOrResolve(artifact, urlId, artifactCache, cachePolicy, timeProvider, result) {
                    val file = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
                        override fun description() = BuildOperationDescriptor
                            .displayName("Access Widening ${result.result}")
                            .progressDisplayName("Access Widener targets: ${accessWidener.targets}")
                            .metadata(BuildOperationCategory.TASK)

                        override fun call(context: BuildOperationContext) = context.callWithStatus {
                            JarAccessWidener.accessWiden(accessWidener, result.result.toPath())
                        }
                    })

                    val output = cacheManager.fileStoreDirectory
                        .resolve(hash.toString())
                        .resolve(id.group)
                        .resolve(id.module)
                        .resolve(id.version)
                        .resolve(checksumService.sha1(file.toFile()).toString())
                        .resolve("${result.result.nameWithoutExtension}-access-widened.${result.result.extension}")

                    output.parent.createDirectories()
                    file.copyTo(output)

                    output.toFile()
                }
            }

            if (!result.hasResult()) {
                result.notFound(artifact.id)
            }
        } else if (artifact is PassthroughAccessWidenedArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.original, moduleSources, result)
        }
    }
}

class AccessWidenedComponentIdentifier(
    val original: ModuleComponentIdentifier,
    val accessWidenersConfiguration: String,
    private val needsSourcesOverride: Boolean? = null
) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Access Widened)"

    override fun toString() = displayName
}
