package net.msrandom.minecraftcodev.remapper.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.callWithStatus
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.getSourceSetConfigurationName
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.addNamed
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.hash
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadata
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadataWrapper
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultCachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributeValue
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class RemappedComponentResolvers @Inject constructor(
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val attributesFactory: ImmutableAttributesFactory,
    private val instantiator: NamedObjectInstantiator,

    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("remapped")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { RemappedArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun wrapMetadata(metadata: ComponentResolveMetadata, identifier: RemappedComponentIdentifier) = metadata.copy(
        identifier,
        {
            val sourceNamespace = attributes.findEntry(MappingsNamespace.attribute.name).takeIf(AttributeValue<*>::isPresent)?.get() as? String

            val category = attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)
            val libraryElements = attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)
            if (category.isPresent && libraryElements.isPresent && category.get() == Category.LIBRARY && libraryElements.get() == LibraryElements.JAR) {
                copy(
                    { oldName ->
                        object : DisplayName {
                            override fun getDisplayName() = "remapped ${oldName.displayName}"
                            override fun getCapitalizedDisplayName() = "Remapped ${oldName.capitalizedDisplayName}"
                        }
                    },
                    attributes.addNamed(attributesFactory, instantiator, MappingsNamespace.attribute, identifier.targetNamespace.name),
                    { dependency ->
                        val namespace = dependency.selector.attributes.getAttribute(MappingsNamespace.attribute)
                        if (namespace != null) {
                            val selector = DefaultModuleComponentSelector.withAttributes(
                                dependency.selector as ModuleComponentSelector, // Unsafe assumption, should be changed if we want things to be more generic.
                                attributesFactory.concat(
                                    (dependency.selector.attributes as AttributeContainerInternal).asImmutable(),
                                    attributesFactory.of(MappingsNamespace.attribute, identifier.targetNamespace)
                                )
                            )

                            RemappedDependencyMetadataWrapper(
                                dependency,
                                selector,
                                identifier.sourceNamespace ?: namespace,
                                identifier.targetNamespace,
                                identifier.mappingsConfiguration,
                                identifier.moduleConfiguration
                            )
                        } else {
                            dependency
                        }
                    },
                    { artifact -> RemappedComponentArtifactMetadata(artifact as ModuleComponentArtifactMetadata, identifier, sourceNamespace) },
                    objects
                )
            } else {
                this
            }
        },
        objects
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is RemappedDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult()) {
                if (result.failure == null) {
                    val metadata = result.metadata
                    val mappingsConfiguration = project.getSourceSetConfigurationName(dependency, MinecraftCodevRemapperPlugin.MAPPINGS_CONFIGURATION)

                    if (result.id is ModuleComponentIdentifier) {
                        val id = RemappedComponentIdentifier(
                            result.id as ModuleComponentIdentifier,
                            dependency.sourceNamespace,
                            dependency.targetNamespace,
                            mappingsConfiguration,
                            dependency.getModuleConfiguration()
                        )

                        if (metadata == null) {
                            result.resolved(id, result.moduleVersionId)
                        } else {
                            result.resolved(wrapMetadata(metadata, id))
                        }
                    }
                }
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is RemappedComponentIdentifier) {
            val newResult = DefaultBuildableComponentResolveResult()
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, newResult)

            if (newResult.hasResult() && newResult.failure == null) {
                result.resolved(wrapMetadata(newResult.metadata, identifier))
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is RemappedComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is RemappedComponentIdentifier) {
        project.extensions
            .getByType(MinecraftCodevExtension::class.java)
            .extensions
            .getByType(RemapperExtension::class.java)
            .loadMappings(project.unsafeResolveConfiguration(project.configurations.getByName((component.id as RemappedComponentIdentifier).mappingsConfiguration)))

        MetadataSourcedComponentArtifacts().getArtifactsFor(
            component, configuration, artifactResolver, hashMapOf(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory
        )
        // resolvers.artifactSelector.resolveArtifacts(CodevGradleLinkageLoader.getDelegate(component), configuration, exclusions, overriddenAttributes)
    } else {
        null
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
        if (component.id is RemappedComponentIdentifier) {
            resolvers.get().artifactResolver.resolveArtifactsWithType(component, artifactType, result)
        }
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        if (artifact is RemappedComponentArtifactMetadata) {
            val id = artifact.componentId

            val mappingFiles = project.unsafeResolveConfiguration(project.configurations.getByName(id.mappingsConfiguration))

            val remapper = project.extensions
                .getByType(MinecraftCodevExtension::class.java)
                .extensions
                .getByType(RemapperExtension::class.java)

            val mappings = remapper.loadMappings(mappingFiles)

            val newResult = DefaultBuildableArtifactResolveResult()
            resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, newResult)

            val sourceNamespace = id.sourceNamespace?.name ?: artifact.namespace ?: MappingsNamespace.OBF

            if (newResult.isSuccessful) {
                val urlId = RemappedArtifactIdentifier(
                    ModuleComponentFileArtifactIdentifier(DefaultModuleComponentIdentifier.newId(id.moduleIdentifier, id.version), newResult.result.name),
                    id.targetNamespace.name,
                    mappings.hash,
                    checksumService.sha1(newResult.result)
                )

                locks.computeIfAbsent(urlId) { ReentrantLock() }.withLock {
                    val cached = artifactCache[urlId]

                    if (cached == null || cachePolicy.artifactExpiry(
                            artifact.delegate.toArtifactIdentifier(),
                            if (cached.isMissing) null else cached.cachedFile,
                            Duration.ofMillis(timeProvider.currentTime - cached.cachedAt),
                            false,
                            artifact.hash() == cached.descriptorHash
                        ).isMustCheck
                    ) {
                        val file = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
                            override fun description() = BuildOperationDescriptor
                                .displayName("Remapping ${newResult.result} from $sourceNamespace to ${id.targetNamespace}")
                                .progressDisplayName("Mappings: ${mappingFiles.joinToString()}")
                                .metadata(BuildOperationCategory.TASK)

                            override fun call(context: BuildOperationContext) = context.callWithStatus {
                                JarRemapper.remap(remapper, mappings.tree, sourceNamespace, id.targetNamespace.name, newResult.result.toPath(), project.files())
                            }
                        })

                        val output = cacheManager.fileStoreDirectory
                            .resolve(mappings.hash.toString())
                            .resolve(id.group)
                            .resolve(id.module)
                            .resolve(id.version)
                            .resolve(checksumService.sha1(file.toFile()).toString())
                            .resolve("${newResult.result.nameWithoutExtension}-${id.targetNamespace.name}.${newResult.result.extension}")

                        output.parent.createDirectories()
                        file.copyTo(output)

                        val outputFile = output.toFile()

                        result.resolved(outputFile)

                        artifactCache[urlId] = DefaultCachedArtifact(outputFile, Instant.now().toEpochMilli(), artifact.hash())
                    } else if (!cached.isMissing) {
                        result.resolved(cached.cachedFile)
                    }
                }
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<RemappedArtifactIdentifier, Lock>()
    }
}

class RemappedComponentIdentifier(
    val original: ModuleComponentIdentifier,
    val sourceNamespace: MappingsNamespace?,
    val targetNamespace: MappingsNamespace,
    val mappingsConfiguration: String,
    val moduleConfiguration: String?
) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "Remapped ${original.displayName} -> $targetNamespace"
}
