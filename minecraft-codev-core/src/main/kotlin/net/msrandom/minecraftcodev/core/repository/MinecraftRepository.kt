package net.msrandom.minecraftcodev.core.repository

import net.msrandom.minecraftcodev.core.utils.getCacheProvider
import org.gradle.api.Action
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.artifacts.ComponentMetadataSupplierDetails
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MetadataSupplierAware
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import org.gradle.api.artifacts.repositories.UrlArtifactRepository
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ConfiguredModuleComponentRepository
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepositoryAccess
import org.gradle.api.internal.artifacts.repositories.AbstractArtifactRepository
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository
import org.gradle.api.internal.artifacts.repositories.descriptor.FlatDirRepositoryDescriptor
import org.gradle.api.internal.artifacts.repositories.resolver.MetadataFetchingCost
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransport
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransportFactory
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.action.InstantiatingAction
import org.gradle.internal.component.external.model.ModuleDependencyMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentOverrideMetadata
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.deprecation.Documentation
import org.gradle.internal.instantiation.InstantiatorFactory
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.resolve.caching.ImplicitInputRecorder
import org.gradle.internal.resolve.caching.ImplicitInputsCapturingInstantiator
import org.gradle.internal.resolve.caching.ImplicitInputsProvidingService
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult
import org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult
import org.gradle.internal.resolve.result.BuildableModuleVersionListingResolveResult
import org.gradle.internal.resource.local.FileStore
import org.gradle.internal.service.DefaultServiceRegistry
import org.gradle.internal.verifier.HttpRedirectVerifierFactory
import java.io.InputStream
import java.net.URI
import javax.inject.Inject

// TODO Handle content filters
interface MinecraftRepository : ArtifactRepository, UrlArtifactRepository, MetadataSupplierAware {
    companion object {
        const val URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    }
}

open class MinecraftRepositoryImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val fileResolver: FileResolver,
    private val transportFactory: RepositoryTransportFactory,
    private val instantiatorFactory: InstantiatorFactory,
    private val gradle: Gradle
) : AbstractArtifactRepository(objectFactory), MinecraftRepository, ResolutionAwareRepository {
    private var url: Any = MinecraftRepository.URL
    private var allowInsecureProtocol = false

    override fun getUrl(): URI = fileResolver.resolveUri(url)

    override fun setUrl(url: URI) {
        this.url = url
    }

    override fun setUrl(url: Any) {
        this.url = url
    }

    override fun isAllowInsecureProtocol() = allowInsecureProtocol

    override fun setAllowInsecureProtocol(allowInsecureProtocol: Boolean) {
        this.allowInsecureProtocol = allowInsecureProtocol
    }

    override fun createResolver() = Resolver(
        name,
        getUrl(),
        transportFactory.createTransport(
            getUrl().scheme,
            name,
            emptyList(),
            redirectVerifier()
        ),
        createInjectorForMetadataSuppliers(),
        gradle,
        objectFactory
    )

    private fun redirectVerifier() = HttpRedirectVerifierFactory.create(
        getUrl(),
        isAllowInsecureProtocol,
        {
            val switchToAdvice = "Switch Minecraft repository '${displayName}' to redirect to a secure protocol (like HTTPS) or allow insecure protocols. "

            val dslMessage = Documentation
                .dslReference(UrlArtifactRepository::class.java, "allowInsecureProtocol")
                .consultDocumentationMessage() + " "

            throw InvalidUserCodeException("Using insecure protocols with repositories, without explicit opt-in, is unsupported. $switchToAdvice$dslMessage")
        },
        {
            val contextualAdvice = "'${getUrl()}' is redirecting to '$it'. "
            val switchToAdvice = "Switch Minecraft repository '$displayName' to redirect to a secure protocol (like HTTPS) or allow insecure protocols. "

            val dslMessage = Documentation
                .dslReference(UrlArtifactRepository::class.java, "allowInsecureProtocol")
                .consultDocumentationMessage() + " "

            throw InvalidUserCodeException("Redirecting from secure protocol to insecure protocol, without explicit opt-in, is unsupported. $contextualAdvice$switchToAdvice$dslMessage")
        }
    )

    override fun getDescriptor() = FlatDirRepositoryDescriptor(name, emptyList())

    private fun createInjectorForMetadataSuppliers(): ImplicitInputsCapturingInstantiator {
        val registry = DefaultServiceRegistry().apply {
            addProvider(object {
                fun createResourceAccessor(): RepositoryResourceAccessor = NoOpRepositoryResourceAccessor()
            })

            add(ObjectFactory::class.java, objectFactory)
        }

        return ImplicitInputsCapturingInstantiator(registry, instantiatorFactory)
    }

    override fun createRepositoryAccessor(transport: RepositoryTransport, rootUri: URI, externalResourcesFileStore: FileStore<String>): RepositoryResourceAccessor =
        NoOpRepositoryResourceAccessor()

    private class NoOpRepositoryResourceAccessor : RepositoryResourceAccessor, ImplicitInputsProvidingService<String, Long, RepositoryResourceAccessor> {
        override fun withResource(relativePath: String, action: Action<in InputStream>) = Unit
        override fun withImplicitInputRecorder(registrar: ImplicitInputRecorder) = this
        override fun isUpToDate(s: String, oldValue: Long?) = true
    }

    class Resolver(private val name: String, val url: URI, val transport: RepositoryTransport, private val injector: Instantiator, gradle: Gradle, objectFactory: ObjectFactory) : ConfiguredModuleComponentRepository {
        val resourceAccessor: DefaultCacheAwareExternalResourceAccessor = objectFactory.newInstance(DefaultCacheAwareExternalResourceAccessor::class.java, transport.repository, getCacheProvider(gradle))

        private val access = NoOpAccess()

        override fun getId() = "minecraft"
        override fun getName() = name
        override fun getLocalAccess() = access
        override fun getRemoteAccess() = access
        override fun getArtifactCache() = throw UnsupportedOperationException()
        override fun getComponentMetadataSupplier(): InstantiatingAction<ComponentMetadataSupplierDetails>? = null
        override fun isDynamicResolveMode() = false
        override fun isLocal() = false
        override fun setComponentResolvers(resolver: ComponentResolvers) = Unit
        override fun getComponentMetadataInstantiator() = injector

        class NoOpAccess : ModuleComponentRepositoryAccess {
            override fun listModuleVersions(dependency: ModuleDependencyMetadata, result: BuildableModuleVersionListingResolveResult) = Unit

            override fun resolveComponentMetaData(
                moduleComponentIdentifier: ModuleComponentIdentifier,
                requestMetaData: ComponentOverrideMetadata,
                result: BuildableModuleComponentMetaDataResolveResult
            ) = result.missing()

            override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) = Unit

            override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult){
                result.notFound(artifact.id)
            }

            override fun estimateMetadataFetchingCost(moduleComponentIdentifier: ModuleComponentIdentifier) = MetadataFetchingCost.CHEAP
        }
    }
}
