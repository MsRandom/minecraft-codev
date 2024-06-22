package net.msrandom.minecraftcodev.forge.resolve

import com.google.common.io.ByteStreams.nullOutputStream
import kotlinx.serialization.json.decodeFromStream
import net.minecraftforge.accesstransformer.TransformerProcessor
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.MinecraftType
import net.msrandom.minecraftcodev.core.ModuleLibraryIdentifier
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.artifactIdSerializer
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentIdentifier
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.resolve.getExtractionState
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.forge.McpConfig
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.PatchLibrary
import net.msrandom.minecraftcodev.forge.Userdev
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import net.msrandom.minecraftcodev.forge.mappings.injectForgeMappingService
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.ProcessOperations
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.CachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.ArtifactResolveException
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Attributes
import java.util.jar.JarFile
import javax.inject.Inject
import kotlin.io.path.*

open class PatchedSetupState
@Inject
constructor(
    private val manifest: MinecraftVersionMetadata,
    private val clientJar: File,
    private val serverJar: File,
    private val userdev: Userdev,
    private val cacheManager: CodevCacheManager,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val processOperations: ProcessOperations,
    private val resolvers: ComponentResolversChainProvider,
    private val objects: ObjectFactory,
    private val repositoriesSupplier: RepositoriesSupplier,
) {
    private val extractionState by lazy {
        getExtractionState(cacheManager, buildOperationExecutor, manifest, { serverJar }) { clientJar }.value!!
    }

    private val mcpConfigFile by lazy {
        resolveArtifact(resolvers, userdev.config.mcp).toPath()
    }

    private val mcpConfig by lazy {
        zipFileSystem(mcpConfigFile).use { fs ->
            fs.base.getPath("config.json").inputStream().use { json.decodeFromStream<McpConfig>(it) }
        }
    }

    private fun merged(): Path =
        buildOperationExecutor.call(
            object : CallableBuildOperation<Path> {
                val merged = Files.createTempFile("merged-", ".tmp.jar")

                override fun description() =
                    BuildOperationDescriptor
                        .displayName("Merging ${clientJar.toPath()} and ${extractionState.result}")
                        .progressDisplayName("Merging to $merged")
                        .metadata(BuildOperationCategory.TASK)

                override fun call(context: BuildOperationContext) =
                    context.callWithStatus {
                        executeMcp(
                            mcpConfig.functions.getValue("merge"),
                            mapOf(
                                "client" to clientJar.absolutePath,
                                "server" to extractionState.result.toAbsolutePath(),
                                "version" to manifest.id,
                                "output" to merged.toAbsolutePath(),
                            ),
                        )

                        merged
                    }
            },
        )

    private fun patched() =
        buildOperationExecutor.call(
            object : CallableBuildOperation<Path> {
                val input = if (userdev.config.notchObf) merged() else this@PatchedSetupState.renamed()
                val patched = Files.createTempFile("patched-", ".tmp.jar")
                val patchesPath = Files.createTempFile("patches-", ".tmp.lzma")

                override fun description() =
                    BuildOperationDescriptor
                        .displayName("Patching $input")
                        .progressDisplayName("Applying binary patches")

                override fun call(context: BuildOperationContext) =
                    context.callWithStatus {
                        // Extract patches
                        zipFileSystem(userdev.source.toPath()).use {
                            it.base.getPath(userdev.config.binpatches).copyTo(patchesPath, StandardCopyOption.REPLACE_EXISTING)
                        }

                        // Binary Patch Minecraft
                        executeMcp(
                            userdev.config.binpatcher,
                            mapOf(
                                "clean" to input.toAbsolutePath(),
                                "output" to patched.toAbsolutePath(),
                                "patch" to patchesPath.toAbsolutePath(),
                            ),
                        )

                        zipFileSystem(patched).use { patchedZip ->
                            // Add missing non-patched files
                            zipFileSystem(input).use { inputZip ->
                                inputZip.base.getPath("/").walk {
                                    for (path in filter(Path::isRegularFile)) {
                                        val output = patchedZip.base.getPath(path.toString())

                                        if (output.notExists()) {
                                            output.parent?.createDirectories()
                                            path.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                                        }
                                    }
                                }
                            }

                            val universal = resolveArtifact(resolvers, userdev.config.universal)
                            val filters = userdev.config.universalFilters.map(::Regex)
                            zipFileSystem(universal.toPath()).use { universalZip ->
                                val root = universalZip.base.getPath("/")
                                root.walk {
                                    for (path in filter(Path::isRegularFile)) {
                                        val name = root.relativize(path).toString()

                                        if (filters.all { name matches it }) {
                                            val output = patchedZip.base.getPath(name)

                                            output.parent?.createDirectories()
                                            path.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                                        }
                                    }
                                }
                            }

                            // Add userdev injects
                            userdev.config.inject?.let { inject ->
                                zipFileSystem(userdev.source.toPath()).use userdev@{ userdevZip ->
                                    val injectPath = userdevZip.base.getPath("/$inject")

                                    if (injectPath.notExists()) {
                                        return@userdev
                                    }

                                    injectPath.walk {
                                        for (path in filter(Path::isRegularFile)) {
                                            val output = patchedZip.base.getPath(injectPath.relativize(path).toString())

                                            output.parent?.createDirectories()
                                            path.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                                        }
                                    }
                                }
                            }
                        }

                        injectForgeMappingService(patched)

                        patched
                    }
            },
        )

    private fun renamed(): Path =
        buildOperationExecutor.call(
            object : CallableBuildOperation<Path> {
                val input = if (userdev.config.notchObf) patched() else merged()
                val renamed = Files.createTempFile("renamed-", ".tmp.jar")

                override fun description() =
                    BuildOperationDescriptor
                        .displayName(
                            "Remapping $input from ${MappingsNamespace.OBF} to ${MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE}",
                        )
                        .progressDisplayName("Remapping to $renamed")
                        .metadata(BuildOperationCategory.TASK)

                override fun call(context: BuildOperationContext) =
                    context.callWithStatus {
                        val mcpConfig = mcpConfig

                        val mappingsFile =
                            zipFileSystem(mcpConfigFile).use {
                                val mappingsPath = it.base.getPath(mcpConfig.data.mappings)
                                if (!mcpConfig.official) {
                                    return@use mappingsPath.copyTo(
                                        Files.createTempFile("mappings-", ".tmp.tsrg"),
                                        StandardCopyOption.REPLACE_EXISTING,
                                    )
                                }

                                val componentResult = DefaultBuildableComponentResolveResult()
                                val artifactResult = DefaultBuildableArtifactResolveResult()

                                val componentId = MinecraftComponentIdentifier(MinecraftType.ClientMappings.module, manifest.id)

                                val repositories =
                                    repositoriesSupplier.get()
                                        .filterIsInstance<MinecraftRepositoryImpl>()
                                        .map(MinecraftRepositoryImpl::createResolver)

                                resolvers.get().componentResolver.resolve(
                                    componentId,
                                    DefaultComponentOverrideMetadata.EMPTY,
                                    componentResult,
                                )

                                objects.newInstance(MinecraftArtifactResolver::class.java, repositories).resolveArtifact(
                                    componentResult.state.prepareForArtifactResolution().resolveMetadata,
                                    DefaultModuleComponentArtifactMetadata(
                                        componentId,
                                        DefaultIvyArtifactName(
                                            MinecraftType.ClientMappings.module,
                                            "txt",
                                            "txt",
                                        ),
                                    ),
                                    artifactResult,
                                )

                                artifactResult.result.file.toPath()
                            }

                        executeMcp(
                            mcpConfig.functions.getValue("rename"),
                            mapOf(
                                "input" to input.toAbsolutePath(),
                                "output" to renamed.toAbsolutePath(),
                                "mappings" to mappingsFile.toAbsolutePath(),
                            ),
                            Triple(
                                "libraries",
                                "-e",
                                extractionState
                                    .libraries
                                    .map { resolveArtifact(resolvers, LibraryInfo(it, null)).toString() },
                            ),
                        )

                        renamed
                    }
            },
        )

    private fun injected() =
        buildOperationExecutor.call(
            object : CallableBuildOperation<Path> {
                val input = if (userdev.config.notchObf) renamed() else patched()
                val injected = Files.createTempFile("injected-", ".tmp.jar")

                override fun description() =
                    BuildOperationDescriptor
                        .displayName("Injecting into $input")
                        .progressDisplayName("Apply Minecraft Injections as $injected")
                        .metadata(BuildOperationCategory.TASK)

                override fun call(context: BuildOperationContext) =
                    context.callWithStatus {
                        val access = Files.createTempFile("access-", ".tmp.txt")
                        val constructors = Files.createTempFile("constructors-", ".tmp.txt")
                        val exceptions = Files.createTempFile("exceptions-", ".tmp.txt")

                        zipFileSystem(mcpConfigFile).use {
                            it.base.getPath(mcpConfig.data.access!!).copyTo(access, StandardCopyOption.REPLACE_EXISTING)
                            it.base.getPath(mcpConfig.data.constructors!!).copyTo(constructors, StandardCopyOption.REPLACE_EXISTING)
                            it.base.getPath(mcpConfig.data.exceptions!!).copyTo(exceptions, StandardCopyOption.REPLACE_EXISTING)
                        }

                        executeMcp(
                            mcpConfig.functions.getValue("mcinject"),
                            mapOf(
                                "input" to input.toAbsolutePath(),
                                "output" to injected.toAbsolutePath(),
                                // TODO logging?
                                "log" to Files.createTempFile("mcinject-log", ".tmp.log").toAbsolutePath(),
                                "exceptions" to exceptions.toAbsolutePath(),
                                "access" to access.toAbsolutePath(),
                                "constructors" to constructors.toAbsolutePath(),
                            ),
                        )

                        injected
                    }
            },
        )

    private fun accessTransformed() =
        buildOperationExecutor.call(
            object : CallableBuildOperation<Path> {
                val input = if (mcpConfig.official) patched() else injected()
                val accessTransformed = Files.createTempFile("access-transformed-", ".tmp.jar")

                val atFiles =
                    zipFileSystem(userdev.source.toPath()).use { fs ->
                        userdev.config.ats.map {
                            val temp = Files.createTempFile("at-", ".tmp.cfg")
                            fs.base.getPath(it).copyTo(temp, StandardCopyOption.REPLACE_EXISTING)

                            temp
                        }
                    }

                override fun description() =
                    BuildOperationDescriptor
                        .displayName("Access Transforming $input")
                        .progressDisplayName("Making access transformed $accessTransformed")
                        .metadata(BuildOperationCategory.TASK)

                @Suppress("UnstableApiUsage")
                override fun call(context: BuildOperationContext) =
                    context.callWithStatus {
                        val out = System.out
                        val err = System.err

                        try {
                            System.setOut(PrintStream(nullOutputStream()))
                            System.setErr(PrintStream(nullOutputStream()))

                            TransformerProcessor.main(
                                "--inJar",
                                input.toAbsolutePath().toString(),
                                "--outJar",
                                accessTransformed.toAbsolutePath().toString(),
                                *atFiles.flatMap {
                                    listOf("--atFile", it.toAbsolutePath().toString())
                                }.toTypedArray(),
                            )
                        } finally {
                            System.setOut(out)
                            System.setErr(err)
                        }

                        accessTransformed
                    }
            },
        )

    private fun executeMcp(
        function: PatchLibrary,
        argumentTemplates: Map<String, Any?>,
        argumentLists: Triple<String, String, Collection<String>>? = null,
    ) {
        val jar = resolveArtifact(resolvers, function.version)

        val mainClass =
            JarFile(jar).use {
                it.manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS)
            }

        val result =
            processOperations.javaexec { spec ->
                spec.classpath(jar)
                spec.mainClass.set(mainClass)

                val args = mutableListOf<String>()

                for (arg in function.args) {
                    if (arg.startsWith('{')) {
                        val template = arg.subSequence(1, arg.length - 1)
                        if (argumentLists != null && template == argumentLists.first) {
                            args.removeLastOrNull()
                            for (s in argumentLists.third) {
                                args.add(argumentLists.second)
                                args.add(s)
                            }
                        } else {
                            if (template in argumentTemplates) {
                                args.add(argumentTemplates[template].toString())
                            } else {
                                // args.add(arg)
                                throw UnsupportedOperationException("Unknown argument for MCP function ${function.args}: $template")
                            }
                        }
                    } else {
                        args.add(arg)
                    }
                }

                spec.args(*args.toTypedArray())

                // TODO maybe add logging instead of nullifying output?
                @Suppress("UnstableApiUsage")
                spec.standardOutput = nullOutputStream()
            }

        result.assertNormalExitValue()
        result.rethrowFailure()
    }

    companion object {
        const val CLIENT_EXTRA = "client-extra"

        private val clientExtrasStates = ConcurrentHashMap<String, File>()
        private val patchedStates = ConcurrentHashMap<Pair<String, File>, File>()
        private val patchedCaches =
            ConcurrentHashMap<Gradle, CodevCacheManager.CachedPath<PatchedArtifactIdentifier, CachedArtifact>.CachedFile>()
        private val minecraftCaches =
            ConcurrentHashMap<Gradle, CodevCacheManager.CachedPath<ComponentArtifactIdentifier, CachedArtifact>.CachedFile>()

        fun getClientExtrasOutput(
            moduleComponentIdentifier: PatchedComponentIdentifier,
            manifest: MinecraftVersionMetadata,
            minecraftCacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            clientJar: File,
            resolvers: ComponentResolversChainProvider,
            project: Project,
        ) = clientExtrasStates.computeIfAbsent(manifest.id) {
            val cache =
                minecraftCaches.computeIfAbsent(project.gradle) {
                    minecraftCacheManager.getMetadataCache(Path("module-artifact"), ::artifactIdSerializer) {
                        CachedArtifactSerializer(minecraftCacheManager.fileStoreDirectory)
                    }.asFile
                }

            val clientId =
                DefaultModuleComponentArtifactIdentifier(
                    moduleComponentIdentifier,
                    moduleComponentIdentifier.module,
                    ArtifactTypeDefinition.ZIP_TYPE,
                    ArtifactTypeDefinition.ZIP_TYPE,
                    CLIENT_EXTRA,
                )

            val componentResult = DefaultBuildableComponentResolveResult()
            val clientResult = DefaultBuildableArtifactResolveResult()

            resolvers.get().componentResolver.resolve(
                moduleComponentIdentifier,
                DefaultComponentOverrideMetadata.EMPTY,
                componentResult,
            )

            MinecraftArtifactResolver.getOrResolve(
                componentResult.state.prepareForArtifactResolution().resolveMetadata,
                DefaultModuleComponentArtifactMetadata(moduleComponentIdentifier, clientId.name),
                project.serviceOf(),
                clientId.asSerializable,
                cache,
                cachePolicy,
                project.serviceOf(),
                clientResult,
            ) {
                val output = clientJar.toPath().createDeterministicCopy(CLIENT_EXTRA, ".tmp.zip")

                zipFileSystem(output).use { clientZip ->
                    clientZip.base.getPath("/").walk {
                        for (path in filter(Path::isRegularFile)) {
                            if (path.toString().endsWith(".class") || path.startsWith("/META-INF")) {
                                path.deleteExisting()
                            }
                        }
                    }
                }

                val checksumService = project.serviceOf<ChecksumService>()
                val path =
                    minecraftCacheManager.fileStoreDirectory
                        .resolve("client-assets")
                        .resolve(manifest.id)
                        .resolve(checksumService.sha1(output.toFile()).toString())
                        .resolve("${manifest.id}-${CLIENT_EXTRA}.${ArtifactTypeDefinition.ZIP_TYPE}")

                path.parent.createDirectories()
                output.copyTo(path, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)

                path.toFile()
            }

            clientResult.result.file
        }

        fun getForgePatchedOutput(
            moduleComponentIdentifier: PatchedComponentIdentifier,
            manifest: MinecraftVersionMetadata,
            clientJar: File,
            serverJar: File,
            userdev: Userdev,
            minecraftCacheManager: CodevCacheManager,
            patchedCacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            resolvers: ComponentResolversChain,
            project: Project,
            objectFactory: ObjectFactory,
        ) = patchedStates.computeIfAbsent(manifest.id to userdev.source) {
            val patchesHash = project.serviceOf<ChecksumService>().sha1(userdev.source)

            val patchedState =
                lazy {
                    objectFactory.newInstance(
                        PatchedSetupState::class.java,
                        manifest,
                        clientJar,
                        serverJar,
                        userdev,
                        minecraftCacheManager,
                    )
                }

            val cache =
                patchedCaches.computeIfAbsent(project.gradle) {
                    patchedCacheManager.getMetadataCache(Path("module-artifact"), { PatchedArtifactIdentifier.ArtifactSerializer }) {
                        CachedArtifactSerializer(patchedCacheManager.fileStoreDirectory)
                    }.asFile
                }

            val forgeId =
                DefaultModuleComponentArtifactIdentifier(
                    moduleComponentIdentifier,
                    moduleComponentIdentifier.module,
                    ArtifactTypeDefinition.JAR_TYPE,
                    ArtifactTypeDefinition.JAR_TYPE,
                )

            val componentResult = DefaultBuildableComponentResolveResult()
            val forgeResult = DefaultBuildableArtifactResolveResult()

            resolvers.componentResolver.resolve(moduleComponentIdentifier, DefaultComponentOverrideMetadata.EMPTY, componentResult)

            MinecraftArtifactResolver.getOrResolve(
                componentResult.state.prepareForArtifactResolution().resolveMetadata,
                DefaultModuleComponentArtifactMetadata(moduleComponentIdentifier, forgeId.name),
                project.serviceOf(),
                PatchedArtifactIdentifier(forgeId.asSerializable, patchesHash),
                cache,
                cachePolicy,
                project.serviceOf(),
                forgeResult,
            ) {
                val patchedStateValue = patchedState.value
                val output = patchedStateValue.accessTransformed()

                zipFileSystem(output).use {
                    addNamespaceManifest(it.base, MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE)
                }

                val checksumService = project.serviceOf<ChecksumService>()
                val path =
                    patchedCacheManager.fileStoreDirectory.resolve(patchesHash.toString())
                        .resolve(manifest.id)
                        .resolve(checksumService.sha1(output.toFile()).toString())
                        .resolve(
                            "forge-${parseLibrary(
                                patchedStateValue.userdev.config.universal,
                            ).id.version}.${ArtifactTypeDefinition.JAR_TYPE}",
                        )

                path.parent.createDirectories()
                output.copyTo(path, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)

                path.toFile()
            }

            forgeResult.result.file
        }

        private fun parseLibrary(library: String): LibraryInfo {
            val extensionIndex = library.indexOf('@')

            val id: ModuleLibraryIdentifier
            val extension: String?
            if (extensionIndex < 0) {
                id = ModuleLibraryIdentifier.load(library)
                extension = null
            } else {
                id = ModuleLibraryIdentifier.load(library.substring(0, extensionIndex))
                extension = library.substring(extensionIndex + 1)
            }

            return LibraryInfo(id, extension)
        }

        fun resolveArtifact(
            resolvers: ComponentResolversChainProvider,
            library: String,
        ) = resolveArtifact(resolvers, parseLibrary(library))

        private fun resolveArtifact(
            resolvers: ComponentResolversChainProvider,
            library: LibraryInfo,
        ): File {
            val id =
                DefaultModuleComponentIdentifier.newId(
                    DefaultModuleIdentifier.newId(library.id.group, library.id.module),
                    library.id.version,
                )
            val componentResult = DefaultBuildableComponentResolveResult()

            resolvers.get().componentResolver.resolve(
                id,
                DefaultComponentOverrideMetadata.EMPTY,
                componentResult,
            )

            if (componentResult.hasResult()) {
                val artifactResult = DefaultBuildableArtifactResolveResult()

                resolvers.get().artifactResolver.resolveArtifact(
                    componentResult.state.prepareForArtifactResolution().resolveMetadata,
                    DefaultModuleComponentArtifactMetadata(
                        id,
                        DefaultIvyArtifactName(
                            library.id.module,
                            ArtifactTypeDefinition.JAR_TYPE,
                            library.extension ?: ArtifactTypeDefinition.JAR_TYPE,
                            library.id.classifier,
                        ),
                    ),
                    artifactResult,
                )

                if (artifactResult.hasResult()) {
                    return artifactResult.result.file
                }
            }

            throw ArtifactResolveException(id, "No artifact found")
        }
    }

    private data class LibraryInfo(val id: ModuleLibraryIdentifier, val extension: String?)
}
