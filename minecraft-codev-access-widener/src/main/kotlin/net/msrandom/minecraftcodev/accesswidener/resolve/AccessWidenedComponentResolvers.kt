package net.msrandom.minecraftcodev.accesswidener.resolve

import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerReader
import net.msrandom.minecraftcodev.accesswidener.AccessWidenedDependencyMetadata
import net.msrandom.minecraftcodev.accesswidener.AccessWidenedDependencyMetadataWrapper
import net.msrandom.minecraftcodev.accesswidener.JarAccessWidener
import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.callWithStatus
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.getSourceSetConfigurationName
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.hash
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultCachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.AttributeValue
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
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
        identifier,
        {
            val namespace = attributes.findEntry(MappingsNamespace.attribute.name).takeIf(AttributeValue<*>::isPresent)?.get() as? String
            val category = attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)
            val libraryElements = attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)
            if (category.isPresent && libraryElements.isPresent && category.get() == Category.LIBRARY && libraryElements.get() == LibraryElements.JAR) {
                copy(
                    { oldName ->
                        object : DisplayName {
                            override fun getDisplayName() = "access widened ${oldName.displayName}"
                            override fun getCapitalizedDisplayName() = "Access Widened ${oldName.capitalizedDisplayName}"
                        }
                    },
                    attributes,
                    { dependency ->
                        if (dependency.selector.attributes.getAttribute(MappingsNamespace.attribute) != null) {
                            // Maybe pass the namespace to use instead of the metadata one?
                            AccessWidenedDependencyMetadataWrapper(
                                dependency,
                                identifier.accessWidenersConfiguration,
                                identifier.moduleConfiguration
                            )
                        } else {
                            dependency
                        }
                    },
                    { artifact -> AccessWidenedComponentArtifactMetadata(artifact as ModuleComponentArtifactMetadata, identifier, namespace) },
                    objects
                )
            } else {
                this
            }
        },
        objects
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is AccessWidenedDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult()) {
                if (result.failure == null) {
                    val metadata = result.metadata
                    val accessWidenersConfiguration = project.getSourceSetConfigurationName(dependency, MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION)

                    if (result.id is ModuleComponentIdentifier) {
                        val id = AccessWidenedComponentIdentifier(
                            result.id as ModuleComponentIdentifier,
                            accessWidenersConfiguration,
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
        if (identifier is AccessWidenedComponentIdentifier) {
            val newResult = DefaultBuildableComponentResolveResult()
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, newResult)

            if (newResult.hasResult() && newResult.failure == null) {
                result.resolved(wrapMetadata(newResult.metadata, identifier))
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
        project.unsafeResolveConfiguration(project.configurations.getByName((component.id as AccessWidenedComponentIdentifier).accessWidenersConfiguration))

        MetadataSourcedComponentArtifacts().getArtifactsFor(
            component, configuration, artifactResolver, hashMapOf(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory
        )
        // resolvers.artifactSelector.resolveArtifacts(CodevGradleLinkageLoader.getDelegate(component), configuration, exclusions, overriddenAttributes)
    } else {
        null
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
        if (component.id is AccessWidenedComponentIdentifier) {
            resolvers.get().artifactResolver.resolveArtifactsWithType(component, artifactType, result)
        }
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        if (artifact is AccessWidenedComponentArtifactMetadata) {
            val id = artifact.componentId
            val newResult = DefaultBuildableArtifactResolveResult()
            resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, newResult)

            if (newResult.isSuccessful) {
                val accessWideners = project.unsafeResolveConfiguration(project.configurations.getByName(id.accessWidenersConfiguration))
                val messageDigest = MessageDigest.getInstance("SHA1")

                val accessWidener = AccessWidener().also { visitor ->
                    val reader = AccessWidenerReader(visitor)
                    for (accessWidener in accessWideners) {
                        DigestInputStream(accessWidener.inputStream(), messageDigest).bufferedReader().use {
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
                    ModuleComponentFileArtifactIdentifier(DefaultModuleComponentIdentifier.newId(id.moduleIdentifier, id.version), newResult.result.name),
                    hash,
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
                                .displayName("Access Widening ${newResult.result}")
                                .progressDisplayName("Access Wideners: ${accessWideners.joinToString()}")
                                .metadata(BuildOperationCategory.TASK)

                            override fun call(context: BuildOperationContext) = context.callWithStatus {
                                JarAccessWidener.accessWiden(accessWidener, newResult.result.toPath())
                            }
                        })

                        val output = cacheManager.fileStoreDirectory
                            .resolve(hash.toString())
                            .resolve(id.group)
                            .resolve(id.module)
                            .resolve(id.version)
                            .resolve(checksumService.sha1(file.toFile()).toString())
                            .resolve("${newResult.result.nameWithoutExtension}-access-widened.${newResult.result.extension}")

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
        private val locks = ConcurrentHashMap<AccessWidenedArtifactIdentifier, Lock>()
    }
}

class AccessWidenedComponentIdentifier(
    val original: ModuleComponentIdentifier,
    val accessWidenersConfiguration: String,
    val moduleConfiguration: String?
) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "Access Widened ${original.displayName}"
}
