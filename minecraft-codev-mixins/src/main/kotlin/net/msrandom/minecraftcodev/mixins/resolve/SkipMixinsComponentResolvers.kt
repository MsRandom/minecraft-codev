package net.msrandom.minecraftcodev.mixins.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.utils.asSerializable
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.core.utils.createDeterministicCopy
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import net.msrandom.minecraftcodev.mixins.dependency.SkipMixinDependencyMetadata
import net.msrandom.minecraftcodev.mixins.dependency.SkipMixinsDependencyMetadataWrapper
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.ArtifactResolveException
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.io.File
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting

open class SkipMixinsComponentResolvers @Inject constructor(
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val checksumService: ChecksumService,
    private val cachePolicy: CachePolicy,
    private val timeProvider: BuildCommencedTimeProvider,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val objects: ObjectFactory,

    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("mixins/skip-mixins")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { SkipMixinsArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun wrapMetadata(metadata: ComponentResolveMetadata, identifier: SkipMixinsComponentIdentifier) = metadata.copy(
        objects,
        identifier,
        {
            val category = attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)

            val shouldWrap = if (category.isPresent) {
                if (category.get() == Category.LIBRARY) {
                    val libraryElements = attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)
                    libraryElements.isPresent && libraryElements.get() == LibraryElements.JAR
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
                            override fun getDisplayName() = "mixins skipped ${oldName.displayName}"
                            override fun getCapitalizedDisplayName() = "Mixins Skipped ${oldName.capitalizedDisplayName}"
                        }
                    },
                    attributes,
                    {
                        map { dependency ->
                            if (dependency.selector.attributes.getAttribute(MappingsNamespace.attribute) != null) {
                                SkipMixinsDependencyMetadataWrapper(dependency)
                            } else {
                                dependency
                            }
                        }
                    },
                    {
                        if (name.type == ArtifactTypeDefinition.JAR_TYPE) {
                            SkipMixinsComponentArtifactMetadata(this as ModuleComponentArtifactMetadata, identifier)
                        } else {
                            PassthroughSkipMixinsArtifactMetadata(this)
                        }
                    }
                )
            } else {
                this
            }
        }
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is SkipMixinDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.metadata

                if (result.id is ModuleComponentIdentifier) {
                    val id = SkipMixinsComponentIdentifier(result.id as ModuleComponentIdentifier)

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
        if (identifier is SkipMixinsComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(wrapMetadata(result.metadata, identifier))
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is SkipMixinsComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is SkipMixinsComponentIdentifier) {
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
        if (artifact is SkipMixinsComponentArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, result)

            if (result.hasResult() && result.failure == null) {
                val urlId = SkipMixinsArtifactIdentifier(
                    artifact.id.asSerializable,
                    checksumService.sha1(result.result)
                )

                getOrResolve(artifact, urlId, artifactCache, cachePolicy, timeProvider, result) {
                    val rules = project.extensions
                        .getByType(MinecraftCodevExtension::class.java).extensions
                        .getByType(MixinsExtension::class.java)
                        .rules

                    buildOperationExecutor.call(object : CallableBuildOperation<File?> {
                        override fun description() = BuildOperationDescriptor
                            .displayName("Removing ${result.result} mixins")
                            .progressDisplayName("Stripping Mixins")
                            .metadata(BuildOperationCategory.TASK)

                        @Suppress("WRONG_NULLABILITY_FOR_JAVA_OVERRIDE")
                        override fun call(context: BuildOperationContext) = context.callWithStatus {
                            val handler = zipFileSystem(result.result.toPath()).use {
                                val path = it.base.getPath("/")

                                rules.get().firstNotNullOfOrNull { rule ->
                                    rule.load(path)
                                }
                            }

                            if (handler == null) {
                                result.failed(
                                    ArtifactResolveException(
                                        artifact.id,
                                        UnsupportedOperationException(
                                            "Couldn't find mixin configs for ${result.result}, unsupported format.\n" +
                                                    "You can register new mixin loading rules with minecraft.mixins.rules"
                                        )
                                    )
                                )

                                null
                            } else {
                                val id = artifact.componentId
                                val file = result.result.toPath().createDeterministicCopy("mixins-skipped", ".tmp.jar")

                                zipFileSystem(file).use {
                                    val root = it.base.getPath("/")
                                    handler.list(root).forEach { path -> root.resolve(path).deleteExisting() }
                                    handler.remove(root)
                                }

                                val output = cacheManager.fileStoreDirectory
                                    .resolve(id.group)
                                    .resolve(id.module)
                                    .resolve(id.version)
                                    .resolve(checksumService.sha1(file.toFile()).toString())
                                    .resolve("${result.result.nameWithoutExtension}-mixins-skipped.${result.result.extension}")

                                output.parent.createDirectories()
                                file.copyTo(output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

                                output.toFile()
                            }
                        }
                    })
                }
            }
        }
    }
}

class SkipMixinsComponentIdentifier(val original: ModuleComponentIdentifier) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Mixins Stripped)"

    override fun toString() = displayName
}
