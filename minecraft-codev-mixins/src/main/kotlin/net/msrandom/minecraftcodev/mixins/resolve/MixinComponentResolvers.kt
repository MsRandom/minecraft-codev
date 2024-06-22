package net.msrandom.minecraftcodev.mixins.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.mixins.MinecraftCodevMixinsPlugin
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import net.msrandom.minecraftcodev.mixins.dependency.MixinDependencyMetadata
import net.msrandom.minecraftcodev.mixins.mixin.GradleMixinService
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSetFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
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
import java.util.function.Supplier
import javax.inject.Inject
import kotlin.io.path.*
import kotlin.math.min

open class MixinComponentResolvers
@Inject
constructor(
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val attributesSchema: AttributesSchemaInternal,
    private val checksumService: ChecksumService,
    private val cachePolicy: CachePolicy,
    private val timeProvider: BuildCommencedTimeProvider,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val objects: ObjectFactory,
    cacheProvider: CodevCacheProvider,
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

    override fun resolve(
        dependency: DependencyMetadata,
        acceptor: VersionSelector?,
        rejector: VersionSelector?,
        result: BuildableComponentIdResolveResult,
    ) {
        if (dependency !is MixinDependencyMetadata) return

        resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

        if (!result.hasResult() || result.failure != null) return

        val metadata = result.state

        if (result.id !is ModuleComponentIdentifier) return

        val mixinsConfiguration =
            dependency.relatedConfiguration.takeUnless(
                String::isEmpty,
            ) ?: MinecraftCodevMixinsPlugin.MIXINS_CONFIGURATION
        val id = MixinComponentIdentifier(result.id as ModuleComponentIdentifier, mixinsConfiguration)

        if (metadata == null) {
            result.resolved(id, result.moduleVersionId)
        } else {
            result.resolved(
                CodevGradleLinkageLoader.wrapComponentMetadata(
                    metadata,
                    MixinComponentMetadataDelegate(id, project),
                    resolvers,
                    objects,
                ),
            )
        }
    }

    override fun resolve(
        identifier: ComponentIdentifier,
        componentOverrideMetadata: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
    ) {
        if (identifier !is MixinComponentIdentifier) return

        resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

        if (!result.hasResult() || result.failure != null) return

        result.resolved(
            CodevGradleLinkageLoader.wrapComponentMetadata(
                result.state,
                MixinComponentMetadataDelegate(identifier, project),
                resolvers,
                objects,
            ),
        )
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) =
        identifier is MixinComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes,
    ) = if (component.id is MixinComponentIdentifier) {
        ArtifactSetFactory.createFromVariantMetadata(
            component.id,
            allVariants,
            legacyVariants,
            attributesSchema,
            overriddenAttributes,
        )
        // resolvers.artifactSelector.resolveArtifacts(CodevGradleLinkageLoader.getDelegate(component), configuration, exclusions, overriddenAttributes)
    } else {
        null
    }

    override fun resolveArtifactsWithType(
        component: ComponentArtifactResolveMetadata,
        artifactType: ArtifactType,
        result: BuildableArtifactSetResolveResult,
    ) {
    }

    override fun resolveArtifact(
        component: ComponentArtifactResolveMetadata,
        artifact: ComponentArtifactMetadata,
        result: BuildableArtifactResolveResult,
    ) {
        if (artifact !is MixinComponentArtifactMetadata) {
            if (artifact is PassthroughArtifactMetadata) {
                resolvers.get().artifactResolver.resolveArtifact(component, artifact.original, result)
            }

            return
        }

        resolvers.get().artifactResolver.resolveArtifact(component, artifact.delegate, result)

        if (!result.hasResult() || result.failure != null) return

        val id = artifact.componentId
        val mixinsConfiguration = project.configurations.getByName(id.mixinsConfiguration)
        val base = result.result

        val files = mutableListOf<Supplier<File>>()
        project.visitConfigurationFiles(resolvers, mixinsConfiguration, null, files::add)

        val urlId =
            MixinArtifactIdentifier(
                artifact.id.asSerializable,
                HashCode.fromBytes(
                    files
                        .map { checksumService.sha1(it.get()).toByteArray() }
                        .reduce { a, b ->
                            ByteArray(min(a.size, b.size)) { (a[it] + b[it]).toByte() }
                        },
                ),
                checksumService.sha1(base.file),
            )

        MinecraftArtifactResolver.getOrResolve(
            component,
            artifact,
            calculatedValueContainerFactory,
            urlId,
            artifactCache,
            cachePolicy,
            timeProvider,
            result,
        ) {
            val rules =
                project.extensions
                    .getByType(MinecraftCodevExtension::class.java).extensions
                    .getByType(MixinsExtension::class.java)
                    .rules

            buildOperationExecutor.call(
                object : CallableBuildOperation<File?> {
                    override fun description() =
                        BuildOperationDescriptor
                            .displayName("Mixing $base")
                            .progressDisplayName("Applying Mixins")
                            .metadata(BuildOperationCategory.TASK)

                    @Suppress("WRONG_NULLABILITY_FOR_JAVA_OVERRIDE")
                    override fun call(context: BuildOperationContext) =
                        context.callWithStatus {
                            val file = base.file.toPath().createDeterministicCopy("mixin", ".tmp.jar")

                            val fileIterable =
                                Iterable {
                                    iterator<File> {
                                        yield(base.file)
                                        yieldAll(files.map(Supplier<File>::get))
                                    }
                                }

                            (MixinService.getService() as GradleMixinService).use(fileIterable, artifact.componentId.module) {
                                for (mixinFile in files) {
                                    val handler =
                                        zipFileSystem(mixinFile.get().toPath()).use {
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
                                                        "You can register new mixin loading rules with minecraft.mixins.rules",
                                                ),
                                            ),
                                        )

                                        return@use null
                                    } else {
                                        zipFileSystem(mixinFile.get().toPath()).use {
                                            val root = it.base.getPath("/")
                                            Mixins.addConfigurations(*handler.list(root).toTypedArray())
                                        }
                                    }
                                }

                                zipFileSystem(file).use {
                                    val root = it.base.getPath("/")

                                    root.walk {
                                        for (path in filter(Path::isRegularFile).filter { path ->
                                            path.toString().endsWith(".class")
                                        }) {
                                            val pathName = root.relativize(path).toString()
                                            val name =
                                                pathName.substring(
                                                    0,
                                                    pathName.length - ".class".length,
                                                ).replace(File.separatorChar, '.')
                                            path.writeBytes(transformer.transformClassBytes(name, name, path.readBytes()))
                                        }
                                    }
                                }

                                val output =
                                    cacheManager.fileStoreDirectory
                                        .resolve(id.group)
                                        .resolve(id.module)
                                        .resolve(id.version)
                                        .resolve(checksumService.sha1(file.toFile()).toString())
                                        .resolve("${base.file.nameWithoutExtension}-mixin.${base.file.extension}")

                                output.parent.createDirectories()
                                file.copyTo(output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

                                output.toFile()
                            }
                        }
                },
            )
        }
    }
}

data class MixinComponentIdentifier(val original: ModuleComponentIdentifier, val mixinsConfiguration: String) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Mixin)"

    override fun toString() = displayName
}
