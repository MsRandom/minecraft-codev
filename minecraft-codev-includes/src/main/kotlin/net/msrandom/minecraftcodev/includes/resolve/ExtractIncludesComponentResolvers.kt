package net.msrandom.minecraftcodev.includes.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.allArtifacts
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import net.msrandom.minecraftcodev.includes.IncludesExtension
import net.msrandom.minecraftcodev.includes.dependency.ExtractIncludesDependencyMetadata
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.ModuleVersionResolveException
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting

open class ExtractIncludesComponentResolvers @Inject constructor(
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
    private val includesExtractedCache = cacheProvider.manager("includes/includes-extracted")
    private val extractedIncludeCache = cacheProvider.manager("includes/extracted-includes")

    private val includesExtractedArtifactCache by lazy {
        includesExtractedCache.getMetadataCache(Path("module-artifact"), MinecraftArtifactResolver.Companion::artifactIdSerializer) {
            CachedArtifactSerializer(includesExtractedCache.fileStoreDirectory)
        }.asFile
    }

    private val extractedIncludeArtifactCache by lazy {
        extractedIncludeCache.getMetadataCache(Path("module-artifact"), { ExtractedIncludeArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(extractedIncludeCache.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun wrapMetadata(metadata: ComponentResolveMetadata, identifier: ExtractIncludesComponentIdentifier) = metadata.copy(objects, identifier, {
        if (attributes.getAttribute(Category.CATEGORY_ATTRIBUTE.name) == Category.LIBRARY && attributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name) == LibraryElements.JAR) {
            val includeRules = project.extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(IncludesExtension::class.java).rules

            val includedArtifacts = mutableListOf<ComponentArtifactMetadata>()
            val extraDependencies = mutableListOf<DependencyMetadata>()
            val extractIncludes = hashSetOf<ComponentArtifactMetadata>()

            // Expensive operations ahead:
            //  We need to either add the included Jars as dependencies, or as artifacts
            //  But... this is metadata resolution, we do not have the artifact, so we can't tell what included Jars there is
            //  Hence, we need to resolve the artifact early(yay), which is why metadata fetching is marked as expensive for this resolver
            //  Another side effect is that this resolver can not be transitive, as that would require transitively resolving artifacts which... yea
            //  So here we go, resolving artifacts early

            for (artifact in allArtifacts) {
                if (artifact is ModuleComponentArtifactMetadata && artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                    val result = DefaultBuildableArtifactResolveResult()
                    resolvers.get().artifactResolver.resolveArtifact(artifact, metadata.sources, result)

                    if (!result.hasResult()) continue

                    val failure = result.failure

                    if (failure != null) {
                        if (artifact.isOptionalArtifact) {
                            continue
                        }

                        throw failure
                    }

                    val included = zipFileSystem(result.result.toPath()).use {
                        val root = it.base.getPath("/")

                        val handler = includeRules.get().firstNotNullOfOrNull { rule ->
                            rule.load(root)
                        } ?: return@use emptyList()

                        handler.list(root)
                    }

                    if (included.isEmpty()) {
                        continue
                    }

                    extractIncludes.add(artifact)

                    for (include in included) {
                        val (path, fileName, group, module, version, versionRange, classifier) = include

                        if (group != null && module != null && (versionRange != null || version != null)) {
                            extraDependencies.add(
                                GradleDependencyMetadata(
                                    DefaultModuleComponentSelector.newSelector(
                                        DefaultModuleIdentifier.newId(group, module), (versionRange ?: version).toString()
                                    ), emptyList(), false, false, null, false, null
                                )
                            )
                        }

                        includedArtifacts.add(
                            ExtractedIncludeComponentArtifactMetadata(
                                artifact,
                                path,
                                identifier,
                                fileName.substringAfterLast('.', ArtifactTypeDefinition.JAR_TYPE),
                                classifier ?: module ?: fileName
                            )
                        )
                    }
                }
            }

            if (extraDependencies.isEmpty() && includedArtifacts.isEmpty()) {
                return@copy this
            }

            val wrapArtifact = { artifact: ComponentArtifactMetadata ->
                if (artifact.name.type == ArtifactTypeDefinition.JAR_TYPE && artifact in extractIncludes) {
                    ExtractIncludesComponentArtifactMetadata(artifact as ModuleComponentArtifactMetadata, identifier)
                } else {
                    PassthroughExtractIncludesArtifactMetadata(artifact)
                }
            }

            copy(
                objects,
                { oldName ->
                    object : DisplayName {
                        override fun getDisplayName() = "includes extracted ${oldName.displayName}"
                        override fun getCapitalizedDisplayName() = "Includes Extracted ${oldName.capitalizedDisplayName}"
                    }
                },
                attributes,
                {
                    this + extraDependencies
                },
                wrapArtifact,
                {
                    map(wrapArtifact) + includedArtifacts
                }
            )
        } else {
            this
        }
    })

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is ExtractIncludesDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.metadata
                if (result.id is ModuleComponentIdentifier) {
                    val id = ExtractIncludesComponentIdentifier(result.id as ModuleComponentIdentifier)

                    if (metadata == null) {
                        result.resolved(id, result.moduleVersionId)
                    } else {
                        try {
                            result.resolved(wrapMetadata(metadata, id))
                        } catch (error: Throwable) {
                            result.failed(ModuleVersionResolveException(dependency.selector, error))
                        }
                    }
                }
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is ExtractIncludesComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                try {
                    result.resolved(wrapMetadata(result.metadata, identifier))
                } catch (error: Throwable) {
                    result.failed(ModuleVersionResolveException(identifier, error))
                }
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = false

    override fun resolveArtifacts(
        component: ComponentResolveMetadata, configuration: ConfigurationMetadata, artifactTypeRegistry: ArtifactTypeRegistry, exclusions: ExcludeSpec, overriddenAttributes: ImmutableAttributes
    ) = if (component.id is ExtractIncludesComponentIdentifier) {
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
        if (artifact is ExtractedIncludeComponentArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.owner, moduleSources, result)

            if (result.isSuccessful) {
                val urlId = ExtractedIncludeArtifactIdentifier(
                    artifact.owner.id, checksumService.sha1(result.result), artifact.path
                )

                getOrResolve(artifact, urlId, extractedIncludeArtifactCache, cachePolicy, timeProvider, result) {
                    buildOperationExecutor.call(object : CallableBuildOperation<Path> {
                        override fun description() =
                            BuildOperationDescriptor
                                .displayName("Extracting ${artifact.path} from ${result.result}")
                                .progressDisplayName("Extracting include from ${result.result}")
                                .metadata(BuildOperationCategory.TASK)

                        override fun call(context: BuildOperationContext) = context.callWithStatus {
                            val file = Files.createTempFile("extracted-include", ".jar")

                            zipFileSystem(result.result.toPath()).use {
                                it.base.getPath(artifact.path).copyTo(file, true)
                            }

                            val output = extractedIncludeCache.fileStoreDirectory
                                .resolve(checksumService.sha1(file.toFile()).toString())
                                .resolve(artifact.path.substringAfterLast('/'))

                            output.parent.createDirectories()
                            file.copyTo(output)

                            output
                        }
                    }).toFile()

                }
            }

            if (!result.hasResult()) {
                result.notFound(artifact.id)
            }
        } else if (artifact is ExtractIncludesComponentArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, result)

            if (result.isSuccessful) {
                val includeRules = project.extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(IncludesExtension::class.java).rules

                val handler = zipFileSystem(result.result.toPath()).use {
                    val root = it.base.getPath("/")

                    includeRules.get().firstNotNullOfOrNull { rule ->
                        rule.load(root)
                    }
                } ?: return

                val id = artifact.componentId

                getOrResolve(artifact, artifact.id.asSerializable, includesExtractedArtifactCache, cachePolicy, timeProvider, result) {
                    buildOperationExecutor.call(object : CallableBuildOperation<Path> {
                        override fun description() = BuildOperationDescriptor
                            .displayName("Removing includes from ${result.result}")
                            .progressDisplayName("Removing extracted includes")
                            .metadata(BuildOperationCategory.TASK)

                        override fun call(context: BuildOperationContext) = context.callWithStatus {
                            val file = Files.createTempFile("includes-extracted", ".jar")

                            result.result.toPath().copyTo(file, true)

                            zipFileSystem(file).use {
                                val root = it.base.getPath("/")

                                for (jar in handler.list(root)) {
                                    it.base.getPath(jar.path).deleteExisting()
                                }

                                handler.remove(root)
                            }

                            val output = includesExtractedCache.fileStoreDirectory
                                .resolve(id.group)
                                .resolve(id.module)
                                .resolve(id.version)
                                .resolve(checksumService.sha1(file.toFile()).toString())
                                .resolve("${result.result.nameWithoutExtension}-without-includes.${result.result.extension}")

                            output.parent.createDirectories()
                            file.copyTo(output)

                            output
                        }
                    }).toFile()
                }

                if (!result.hasResult()) {
                    result.notFound(artifact.id)
                }
            }
        } else if (artifact is PassthroughExtractIncludesArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.original, moduleSources, result)
        }
    }
}

class ExtractIncludesComponentIdentifier(val original: ModuleComponentIdentifier) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Includes Extracted)"

    override fun toString() = displayName
}
