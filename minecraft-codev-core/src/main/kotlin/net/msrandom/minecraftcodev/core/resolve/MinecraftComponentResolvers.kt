package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.wrapComponentMetadata
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSetFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentArtifactResolveMetadata
import org.gradle.internal.component.model.ComponentArtifactResolveVariantState
import org.gradle.internal.component.model.ComponentOverrideMetadata
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.hash.HashCode
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.internal.snapshot.impl.CoercingStringValueSnapshot
import org.gradle.util.internal.BuildCommencedTimeProvider
import javax.inject.Inject

open class MinecraftComponentResolvers
@Inject
constructor(
    private val cachePolicy: CachePolicy,
    private val objects: ObjectFactory,
    private val attributesSchema: AttributesSchemaInternal,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    cacheProvider: CodevCacheProvider,
    repositoriesSupplier: RepositoriesSupplier,
) : ComponentResolvers, ComponentMetaDataResolver, OriginArtifactSelector {
    private val repositories =
        repositoriesSupplier.get()
            .filterIsInstance<MinecraftRepositoryImpl>()
            .map(MinecraftRepositoryImpl::createResolver)

    private val componentIdResolver = objects.newInstance(MinecraftDependencyToComponentIdResolver::class.java, repositories)
    private val artifactResolver = objects.newInstance(MinecraftArtifactResolver::class.java, repositories)

    private val cacheManager = cacheProvider.manager("minecraft")

    override fun getComponentIdResolver(): DependencyToComponentIdResolver = componentIdResolver

    override fun getComponentResolver() = this

    override fun getArtifactSelector() = this

    override fun getArtifactResolver(): ArtifactResolver = artifactResolver

    override fun resolve(
        identifier: ComponentIdentifier,
        componentOverrideMetadata: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
    ) {
        if (identifier::class == MinecraftComponentIdentifier::class) {
            identifier as MinecraftComponentIdentifier

            var versionList: MinecraftVersionList? = null

            for (repository in repositories) {
                versionList =
                    MinecraftMetadataGenerator.getVersionList(
                        identifier,
                        null,
                        repository.url,
                        cacheManager,
                        cachePolicy,
                        repository.resourceAccessor,
                        checksumService,
                        timeProvider,
                        null,
                    )

                if (versionList != null) {
                    break
                }
            }

            if (versionList == null) {
                return
            }

            var isChanging = false
            val id =
                if (identifier.version.endsWith("-SNAPSHOT")) {
                    val snapshot = versionList.snapshot(identifier.version.substring(0, identifier.version.length - "-SNAPSHOT".length))

                    if (snapshot == null) {
                        result.notFound(
                            DefaultModuleComponentIdentifier.newId(
                                DefaultModuleIdentifier.newId(GROUP, identifier.module),
                                identifier.version,
                            ),
                        )
                        return
                    }

                    isChanging = true
                    MinecraftComponentIdentifier(identifier.module, snapshot)
                } else {
                    val match = UNIQUE_VERSION_ID.find(identifier.version)?.groups?.get(1)?.value

                    if (match == null) {
                        identifier
                    } else {
                        val version = versionList.snapshotTimestamps[match]
                        if (version == null) {
                            result.notFound(
                                DefaultModuleComponentIdentifier.newId(
                                    DefaultModuleIdentifier.newId(GROUP, identifier.module),
                                    identifier.version,
                                ),
                            )
                            return
                        }

                        MinecraftComponentIdentifier(identifier.module, version)
                    }
                }

            val metadataGenerator = objects.newInstance(MinecraftMetadataGenerator::class.java, cacheManager)

            for (repository in repositories) {
                val metadata =
                    metadataGenerator.resolveMetadata(
                        repository,
                        repository.resourceAccessor,
                        id,
                        isChanging,
                        componentOverrideMetadata,
                        result::attempted,
                    )

                if (metadata != null) {
                    result.resolved(wrapComponentMetadata(metadata, objects))

                    break
                }
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = false

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec?,
        overriddenAttributes: ImmutableAttributes,
    ): ArtifactSet? {
        val componentIdentifier = component.id

        return if (componentIdentifier::class == MinecraftComponentIdentifier::class) {
            componentIdentifier as MinecraftComponentIdentifier

            // Direct server downloads are not allowed, as the common dependency should be used for that instead
            val valid =
                if (componentIdentifier.module != SERVER_DOWNLOAD) {
                    if (componentIdentifier.module == CLIENT_MODULE) {
                        true
                    } else {
                        var exists = false

                        for (repository in repositories) {
                            val manifest =
                                MinecraftMetadataGenerator.getVersionManifest(
                                    componentIdentifier,
                                    repository.url,
                                    cacheManager,
                                    cachePolicy,
                                    repository.resourceAccessor,
                                    checksumService,
                                    timeProvider,
                                    null,
                                )

                            if (manifest != null) {
                                if (componentIdentifier.module == COMMON_MODULE) {
                                    if (SERVER_DOWNLOAD in manifest.downloads) {
                                        exists = true
                                        break
                                    }
                                } else {
                                    val fixedName = componentIdentifier.module.asMinecraftDownload()

                                    if (fixedName != SERVER_DOWNLOAD && (fixedName in manifest.downloads || componentIdentifier.module == CLIENT_NATIVES_MODULE)) {
                                        exists = true
                                        break
                                    }
                                }
                            }
                        }

                        exists
                    }
                } else {
                    false
                }

            if (valid) {
                return ArtifactSetFactory.createFromVariantMetadata(
                    componentIdentifier,
                    allVariants,
                    legacyVariants,
                    attributesSchema,
                    overriddenAttributes,
                )
            } else {
                null
            }
        } else {
            null
        }
    }

    companion object {
        const val GROUP = "net.minecraft"

        const val COMMON_MODULE = "common"
        const val CLIENT_MODULE = "client"
        const val CLIENT_NATIVES_MODULE = "client-natives"
        const val CLIENT_DOWNLOAD = "client"
        const val SERVER_DOWNLOAD = "server"

        private val UNIQUE_VERSION_ID = Regex(".+-(\\d{8}\\.\\d{6}-\\d+)")

        fun ImmutableAttributes.addNamed(
            attributesFactory: ImmutableAttributesFactory,
            instantiator: NamedObjectInstantiator,
            attribute: Attribute<*>,
            value: String,
        ): ImmutableAttributes =
            attributesFactory.concat(
                this,
                Attribute.of(attribute.name, String::class.java),
                CoercingStringValueSnapshot(value, instantiator),
            )

        internal fun String.asMinecraftDownload() = takeUnless { contains('_') }?.replace('-', '_')

        fun ComponentArtifactMetadata.hash(): HashCode =
            HashCode.fromBytes(
                name.hashCode().let {
                    byteArrayOf(
                        (it and 0xFF).toByte(),
                        (it shl 8 and 0xFF).toByte(),
                        (it shl 16 and 0xFF).toByte(),
                        (it shl 24 and 0xFF).toByte(),
                    )
                },
            )
    }
}

open class MinecraftComponentIdentifier(private val module: String, private val version: String) : ModuleComponentIdentifier {
    open val isBase
        get() = module == MinecraftComponentResolvers.COMMON_MODULE

    override fun getDisplayName() = "Minecraft ${getModule()} ${getVersion()}"

    override fun getGroup() = MinecraftComponentResolvers.GROUP

    override fun getModule() = module

    override fun getVersion() = version

    override fun getModuleIdentifier(): ModuleIdentifier = DefaultModuleIdentifier.newId(group, module)

    override fun toString() = displayName

    override fun equals(other: Any?) =
        other?.javaClass == MinecraftComponentIdentifier::class.java &&
            module == (other as MinecraftComponentIdentifier).module &&
            version == other.version

    override fun hashCode() = version.hashCode() + module.hashCode() * 31
}

interface MainArtifact
