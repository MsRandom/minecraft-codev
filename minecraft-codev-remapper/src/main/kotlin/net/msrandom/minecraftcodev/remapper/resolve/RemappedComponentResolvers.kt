package net.msrandom.minecraftcodev.remapper.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.hash
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.RemappedDependencyMetadata
import net.msrandom.minecraftcodev.remapper.RemappedDependencyMetadataWrapper
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.CachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultCachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.internal.serialize.MapSerializer
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class RemappedComponentResolvers @Inject constructor(
    private val resolvers: ComponentResolversChain,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,

    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("remapped")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), emptyMap<RemappedArtifactIdentifier, CachedArtifact>()) {
            MapSerializer(RemappedArtifactIdentifier.ArtifactSerializer, CachedArtifactSerializer(cacheManager.fileStoreDirectory))
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is RemappedDependencyMetadata) {
            resolvers.componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult()) {
                if (result.failure == null) {
                    val metadata = result.metadata
                    val mappingsConfiguration = dependency.mappingsConfiguration ?: run {
                        val moduleConfiguration = dependency.getModuleConfiguration()

                        if (moduleConfiguration == null) {
                            RemapperExtension.MAPPINGS_CONFIGURATION
                        } else {
                            var owningSourceSet: SourceSet? = null
                            for (sourceSet in project.extensions.getByType(SourceSetContainer::class.java)) {
                                if (moduleConfiguration.startsWith(sourceSet.name)) {
                                    owningSourceSet = sourceSet
                                    break
                                }
                            }

                            // FIXME don't use .capitalized
                            owningSourceSet?.let { "${it.name}${RemapperExtension.MAPPINGS_CONFIGURATION.capitalized()}" } ?: RemapperExtension.MAPPINGS_CONFIGURATION
                        }
                    }

                    if (result.id is ModuleComponentIdentifier) {
                        if (metadata == null) {
                            result.resolved(
                                RemappedComponentIdentifier(
                                    result.id as ModuleComponentIdentifier,
                                    dependency.sourceNamespace,
                                    dependency.targetNamespace,
                                    mappingsConfiguration,
                                    dependency.getModuleConfiguration()
                                ),
                                result.moduleVersionId
                            )
                        } else {
                            // FIXME
                            result.resolved(object : ComponentResolveMetadata by metadata {
                                override fun getId() = RemappedComponentIdentifier(
                                    result.id as ModuleComponentIdentifier,
                                    dependency.sourceNamespace,
                                    dependency.targetNamespace,
                                    mappingsConfiguration,
                                    dependency.getModuleConfiguration()
                                )
                            })
                        }
                    }
                }
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is RemappedComponentIdentifier) {
            val newResult = DefaultBuildableComponentResolveResult()
            resolvers.componentResolver.resolve(identifier.original, componentOverrideMetadata, newResult)

            if (newResult.hasResult() && newResult.failure == null) {
                val existingMetadata = newResult.metadata

                val metadata = CodevGradleLinkageLoader.wrapComponentMetadata(
                    existingMetadata,
                    identifier,
                    {
                        val newVariants = mutableMapOf<String, ConfigurationMetadata>()

                        for ((key, variant) in it) {
                            if (variant != null) {
                                val category = variant.attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)
                                val libraryElements = variant.attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)
                                if (category.isPresent && libraryElements.isPresent && category.get() == Category.LIBRARY && libraryElements.get() == LibraryElements.JAR) {
                                    newVariants[key] = CodevGradleLinkageLoader.wrapConfigurationMetadata(
                                        variant,
                                        { oldName ->
                                            object : DisplayName {
                                                override fun getDisplayName() = "remapped ${oldName.displayName}"
                                                override fun getCapitalizedDisplayName() = "Remapped ${oldName.capitalizedDisplayName}"
                                            }
                                        },
                                        { dependencies ->
                                            dependencies.map { dependency ->
                                                val namespace = dependency.selector.attributes.getAttribute(MappingsNamespace.attribute)
                                                if (namespace != null) {
                                                    RemappedDependencyMetadataWrapper(
                                                        dependency,
                                                        identifier.sourceNamespace ?: namespace,
                                                        identifier.targetNamespace,
                                                        identifier.mappingsConfiguration,
                                                        identifier.moduleConfiguration
                                                    )
                                                } else {
                                                    dependency
                                                }
                                            }
                                        },
                                        { artifact -> RemappedComponentArtifactMetadata(artifact, identifier) },
                                        objects
                                    )
                                }
                            }
                        }

                        it + newVariants
                    },
                    objects
                )

                result.resolved(metadata)
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is RemappedComponentIdentifier && resolvers.componentResolver.isFetchingMetadataCheap(identifier)

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
            resolvers.artifactResolver.resolveArtifactsWithType(component, artifactType, result)
        }
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        if (artifact is RemappedComponentArtifactMetadata) {
            val id = artifact.componentId

            val mappings = project.extensions
                .getByType(MinecraftCodevExtension::class.java)
                .extensions
                .getByType(RemapperExtension::class.java)
                .loadMappings(project.configurations.getByName(id.mappingsConfiguration))

            val newResult = DefaultBuildableArtifactResolveResult()
            resolvers.artifactResolver.resolveArtifact(artifact.delegate, moduleSources, newResult)

            if (newResult.isSuccessful) {
                val cachedValues = artifactCache.value

                val urlId = RemappedArtifactIdentifier(
                    ModuleComponentFileArtifactIdentifier(DefaultModuleComponentIdentifier.newId(id.moduleIdentifier, id.version), newResult.result.name),
                    id.targetNamespace.name,
                    mappings.hash,
                    checksumService.sha1(newResult.result)
                )

                val cached = cachedValues[urlId]

                if (cached == null || cachePolicy.artifactExpiry(
                        (artifact.delegate as ModuleComponentArtifactMetadata).toArtifactIdentifier(),
                        if (cached.isMissing) null else cached.cachedFile,
                        Duration.ofMillis(timeProvider.currentTime - cached.cachedAt),
                        false,
                        artifact.hash() == cached.descriptorHash
                    ).isMustCheck
                ) {
                    val file = JarRemapper.remap(mappings.tree, id.sourceNamespace?.name ?: MappingsNamespace.OBF, id.targetNamespace.name, newResult.result.toPath(), emptyList())

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

                    artifactCache.update(
                        cachedValues + (urlId to DefaultCachedArtifact(
                            outputFile,
                            Instant.now().toEpochMilli(),
                            artifact.hash()
                        ))
                    )
                } else if (!cached.isMissing) {
                    result.resolved(cached.cachedFile)
                }
            }
        }
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
