package net.msrandom.minecraftcodev.mixins.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import net.msrandom.minecraftcodev.mixins.MinecraftCodevMixinsPlugin
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import net.msrandom.minecraftcodev.mixins.dependency.MixinDependencyMetadata
import net.msrandom.minecraftcodev.mixins.dependency.MixinDependencyMetadataWrapper
import net.msrandom.minecraftcodev.mixins.mixin.GradleMixinService
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
import org.gradle.internal.hash.HashCode
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
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.service.MixinService
import java.io.File
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.*
import kotlin.math.min

open class MixinComponentResolvers @Inject constructor(
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
    private val cacheManager = cacheProvider.manager("mixins/mixin")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { MixinArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun wrapMetadata(metadata: ComponentResolveMetadata, identifier: MixinComponentIdentifier) = metadata.copy(
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
                            override fun getDisplayName() = "mixin ${oldName.displayName}"
                            override fun getCapitalizedDisplayName() = "Mixin ${oldName.capitalizedDisplayName}"
                        }
                    },
                    attributes,
                    {
                        map { dependency ->
                            if (dependency.selector.attributes.getAttribute(MappingsNamespace.attribute) != null) {
                                MixinDependencyMetadataWrapper(dependency, identifier.mixinsConfiguration)
                            } else {
                                dependency
                            }
                        }
                    },
                    {
                        if (name.type == ArtifactTypeDefinition.JAR_TYPE) {
                            MixinComponentArtifactMetadata(this as ModuleComponentArtifactMetadata, identifier)
                        } else {
                            PassthroughMixinArtifactMetadata(this)
                        }
                    }
                )
            } else {
                this
            }
        }
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is MixinDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.metadata

                if (result.id is ModuleComponentIdentifier) {
                    val mixinsConfiguration = dependency.relatedConfiguration ?: MinecraftCodevMixinsPlugin.MIXINS_CONFIGURATION
                    val id = MixinComponentIdentifier(result.id as ModuleComponentIdentifier, mixinsConfiguration)

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
        if (identifier is MixinComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(wrapMetadata(result.metadata, identifier))
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is MixinComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is MixinComponentIdentifier) {
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
        if (artifact is MixinComponentArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, result)

            if (result.hasResult() && result.failure == null) {
                val id = artifact.componentId
                val mixinsConfiguration = project.configurations.getByName(id.mixinsConfiguration)

                val files = mutableListOf<File>()
                project.visitConfigurationFiles(resolvers, mixinsConfiguration, null, files::add)

                val urlId = MixinArtifactIdentifier(
                    artifact.id.asSerializable,
                    HashCode.fromBytes(files.map { checksumService.sha1(it).toByteArray() }.reduce { a, b -> ByteArray(min(a.size, b.size)) { (a[it] + b[it]).toByte() } }),
                    checksumService.sha1(result.result)
                )

                MinecraftArtifactResolver.getOrResolve(artifact, urlId, artifactCache, cachePolicy, timeProvider, result) {
                    val rules = project.extensions
                        .getByType(MinecraftCodevExtension::class.java).extensions
                        .getByType(MixinsExtension::class.java)
                        .rules

                    buildOperationExecutor.call(object : CallableBuildOperation<File?> {
                        override fun description() = BuildOperationDescriptor
                            .displayName("Mixing ${result.result}")
                            .progressDisplayName("Applying Mixins")
                            .metadata(BuildOperationCategory.TASK)

                        @Suppress("WRONG_NULLABILITY_FOR_JAVA_OVERRIDE")
                        override fun call(context: BuildOperationContext) = context.callWithStatus {
                            val file = result.result.toPath().createDeterministicCopy("mixin", ".tmp.jar")

                            val fileIterable = Iterable {
                                iterator<File> {
                                    yield(result.result)
                                    yieldAll(files)
                                }
                            }

                            (MixinService.getService() as GradleMixinService).use(fileIterable, artifact.componentId.module) {
                                for (mixinFile in files) {
                                    val handler = zipFileSystem(mixinFile.toPath()).use {
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

                                        return@use null
                                    } else {
                                        zipFileSystem(mixinFile.toPath()).use {
                                            val root = it.base.getPath("/")
                                            Mixins.addConfigurations(*handler.list(root).toTypedArray())
                                        }
                                    }
                                }

                                zipFileSystem(file).use {
                                    val root = it.base.getPath("/")

                                    root.walk {
                                        for (path in filter(Path::isRegularFile).filter { path -> path.toString().endsWith(".class") }) {
                                            val pathName = root.relativize(path).toString()
                                            val name = pathName.substring(0, pathName.length - ".class".length).replace(File.separatorChar, '.')
                                            path.writeBytes(transformer.transformClassBytes(name, name, path.readBytes()))
                                        }
                                    }
                                }

                                val output = cacheManager.fileStoreDirectory
                                    .resolve(id.group)
                                    .resolve(id.module)
                                    .resolve(id.version)
                                    .resolve(checksumService.sha1(file.toFile()).toString())
                                    .resolve("${result.result.nameWithoutExtension}-mixin.${result.result.extension}")

                                output.parent.createDirectories()
                                file.copyTo(output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

                                output.toFile()
                            }
                        }
                    })
                }
            }
        } else if (artifact is PassthroughMixinArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.original, moduleSources, result)
        }
    }
}

class MixinComponentIdentifier(val original: ModuleComponentIdentifier, val mixinsConfiguration: String) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Mixin)"

    override fun toString() = displayName
}
