package net.msrandom.minecraftcodev.forge.resolve

import com.google.common.base.CaseFormat
import com.google.common.io.ByteStreams.nullOutputStream
import kotlinx.serialization.json.decodeFromStream
import net.minecraftforge.accesstransformer.TransformerProcessor
import net.minecraftforge.srgutils.IMappingFile
import net.minecraftforge.srgutils.INamedMappingFile
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.resolve.getExtractionState
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter.withAssets
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.forge.*
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.ProcessOperations
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.tasks.AbstractTaskDependency
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.TaskDependency
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.hash.HashCode
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.util.internal.BuildCommencedTimeProvider
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

open class PatchedSetupState @Inject constructor(
    private val moduleComponentIdentifier: PatchedComponentIdentifier,
    private val manifest: MinecraftVersionMetadata,
    private val clientJar: File,
    private val serverJar: File,
    private val userdevConfigFile: File,
    private val patchesHash: HashCode,
    private val cacheManager: CodevCacheManager,

    private val project: Project,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val processOperations: ProcessOperations,
    private val cachePolicy: CachePolicy,
    private val timeProvider: BuildCommencedTimeProvider,
    private val checksumService: ChecksumService
) {
    private val extractionState = getExtractionState(buildOperationExecutor, manifest, serverJar) { clientJar }

    private val userdevConfig by lazy {
        zipFileSystem(userdevConfigFile.toPath()).use { fs ->
            fs.getPath("config.json").inputStream().use { json.decodeFromStream<UserdevConfig>(it) }
        }
    }

    private val mcpConfigFile by lazy {
        project.unsafeResolveConfiguration(project.configurations.createIfAbsent(configurationName(userdevConfig.mcp)) {
            it.dependencies.add(project.dependencies.create(userdevConfig.mcp))
            it.isVisible = false
        }).singleFile.toPath()
    }

    private val forgeUniversalConfiguration = project.configurations.createIfAbsent(configurationName(userdevConfig.universal)) {
        it.dependencies.add(project.dependencies.create(userdevConfig.universal))
        it.isTransitive = false
        it.isVisible = false
    }

    private val commonClasspathConfiguration by lazy {
        project.configurations.createIfAbsent("${manifest.id}-commonClasspath") {
            for (library in extractionState.value.libraries) {
                it.dependencies.add(project.dependencies.create(library.toString()))
            }
        }
    }

    private val mergeFunction by lazy {
        mcpConfig.functions.getValue("merge")
    }

    private val renameFunction by lazy {
        mcpConfig.functions.getValue("rename")
    }

    private val mcInjectFunction by lazy {
        mcpConfig.functions["mcinject"]
    }

    private val mcpConfig by lazy {
        zipFileSystem(mcpConfigFile).use { fs ->
            fs.getPath("config.json").inputStream().use { json.decodeFromStream<McpConfig>(it) }
        }
    }

    private val mappings by lazy {
        val mcpConfig = mcpConfig
        zipFileSystem(mcpConfigFile).use {
            val mappingsPath = it.getPath(mcpConfig.data.mappings)
            val mappings = mappingsPath.inputStream().use(INamedMappingFile::load)

            if (mcpConfig.official) {
                // Filter class names and fix them
                TODO("Not yet implemented")
            }

            mappings
        }
    }

    val taskDependencies: TaskDependency
        get() = object : AbstractTaskDependency() {
            override fun visitDependencies(context: TaskDependencyResolveContext) {
                project.addConfigurationResolutionDependencies(context, forgeUniversalConfiguration)
                project.addConfigurationResolutionDependencies(context, commonClasspathConfiguration)
                project.addConfigurationResolutionDependencies(context, functionConfiguration(userdevConfig.binpatcher))
                project.addConfigurationResolutionDependencies(context, functionConfiguration(mergeFunction))
                project.addConfigurationResolutionDependencies(context, functionConfiguration(renameFunction))

                mcInjectFunction?.let {
                    project.addConfigurationResolutionDependencies(context, functionConfiguration(it))
                }
            }
        }

    private fun functionConfiguration(function: PatchLibrary) = project.configurations.createIfAbsent(configurationName(function.version)) {
        it.dependencies.add(project.dependencies.create(function.version))
        it.isTransitive = false
        it.isVisible = false
    }

    private fun merged(): Path = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val merged = Files.createTempFile("merged-", ".tmp.jar")

        override fun description() = BuildOperationDescriptor
            .displayName("Merging ${clientJar.toPath()} and ${extractionState.value.result}")
            .progressDisplayName("Merging to $merged")
            .metadata(BuildOperationCategory.TASK)

        override fun call(context: BuildOperationContext) = context.callWithStatus {
            executeMcp(
                mergeFunction,
                mapOf(
                    "client" to clientJar.absolutePath,
                    "server" to extractionState.value.result.toAbsolutePath(),
                    "version" to manifest.id,
                    "output" to merged.toAbsolutePath()
                )
            )

            merged
        }
    })

    private fun patched() = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val input = if (userdevConfig.notchObf) merged() else this@PatchedSetupState.renamed()
        val patched = Files.createTempFile("patched-", ".tmp.jar")
        val patchesPath = Files.createTempFile("patches-", ".tmp.lzma")

        override fun description() = BuildOperationDescriptor
            .displayName("Patching $input")
            .progressDisplayName("Applying binary patches")

        override fun call(context: BuildOperationContext) = context.callWithStatus {
            // Extract patches
            val userdevConfig = userdevConfig
            zipFileSystem(userdevConfigFile.toPath()).use {
                it.getPath(userdevConfig.binpatches).copyTo(patchesPath, StandardCopyOption.REPLACE_EXISTING)
            }

            // Binary Patch Minecraft
            executeMcp(
                userdevConfig.binpatcher,
                mapOf(
                    "clean" to input.toAbsolutePath(),
                    "output" to patched.toAbsolutePath(),
                    "patch" to patchesPath.toAbsolutePath(),
                )
            )

            zipFileSystem(patched).use { patchedZip ->
                // Add missing non-patched files
                zipFileSystem(input).use { inputZip ->
                    inputZip.getPath("/").walk {
                        for (path in filter(Path::isRegularFile)) {
                            val output = patchedZip.getPath(path.toString())

                            if (output.notExists()) {
                                output.parent?.createDirectories()
                                path.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                            }
                        }
                    }
                }

                // Add files from the universal Jar
                project.configurations.createIfAbsent(userdevConfig.universal.replace(":", "")) {
                    it.dependencies.add(project.dependencies.create(userdevConfig.universal))
                }

                val universal = forgeUniversalConfiguration.singleFile
                val filters = userdevConfig.universalFilters.map(::Regex)
                zipFileSystem(universal.toPath()).use { universalZip ->
                    val root = universalZip.getPath("/")
                    root.walk {
                        for (path in filter(Path::isRegularFile)) {
                            val name = root.relativize(path).toString()

                            if (filters.all { name matches it }) {
                                val output = patchedZip.getPath(name)

                                output.parent?.createDirectories()
                                path.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                            }
                        }
                    }
                }

                // Add userdev injects
                userdevConfig.inject?.let { inject ->
                    zipFileSystem(userdevConfigFile.toPath()).use { userdevZip ->
                        val injectPath = userdevZip.getPath(inject)
                        if (injectPath.exists()) {
                            injectPath.walk {
                                for (path in filter(Path::isRegularFile)) {
                                    val output = patchedZip.getPath(injectPath.relativize(path).toString())

                                    output.parent?.createDirectories()
                                    path.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                                }
                            }
                        }
                    }
                }
            }

            patched
        }
    })

    private fun renamed(): Path = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val input = if (userdevConfig.notchObf) patched() else merged()
        val renamed = Files.createTempFile("renamed-", ".tmp.jar")
        val fixedMappingsPath = Files.createTempFile("mappings-", ".tmp.tsrg")

        override fun description() = BuildOperationDescriptor
            .displayName("Remapping $input from ${MappingsNamespace.OBF} to ${MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE}")
            .progressDisplayName("Remapping to $renamed")
            .metadata(BuildOperationCategory.TASK)

        override fun call(context: BuildOperationContext) = context.callWithStatus {
            val mappings = mappings
            zipFileSystem(mcpConfigFile).use {
                val mappingsPath = it.getPath(mcpConfig.data.mappings)
                if (mcpConfig.official) {
                    mappings.write(fixedMappingsPath, IMappingFile.Format.TSRG2)
                } else {
                    mappingsPath.copyTo(fixedMappingsPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            executeMcp(
                renameFunction,
                mapOf(
                    "input" to input.toAbsolutePath(),
                    "output" to renamed.toAbsolutePath(),
                    "mappings" to fixedMappingsPath.toAbsolutePath()
                ),
                Triple(
                    "libraries",
                    "-e",
                    commonClasspathConfiguration.files.map {
                        it.absolutePath
                    }
                )
            )

            renamed
        }
    })

    private fun injected() = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val input = if (userdevConfig.notchObf) renamed() else patched()
        val injected = Files.createTempFile("injected-", ".tmp.jar")

        override fun description() = BuildOperationDescriptor
            .displayName("Injecting into $input")
            .progressDisplayName("Apply Minecraft Injections as $injected")
            .metadata(BuildOperationCategory.TASK)

        override fun call(context: BuildOperationContext) = context.callWithStatus {
            val access = Files.createTempFile("access-", ".tmp.txt")
            val constructors = Files.createTempFile("constructors-", ".tmp.txt")
            val exceptions = Files.createTempFile("exceptions-", ".tmp.txt")

            zipFileSystem(mcpConfigFile).use {
                it.getPath(mcpConfig.data.access!!).copyTo(access, StandardCopyOption.REPLACE_EXISTING)
                it.getPath(mcpConfig.data.constructors!!).copyTo(constructors, StandardCopyOption.REPLACE_EXISTING)
                it.getPath(mcpConfig.data.exceptions!!).copyTo(exceptions, StandardCopyOption.REPLACE_EXISTING)
            }

            executeMcp(
                mcInjectFunction!!,
                mapOf(
                    "input" to input.toAbsolutePath(),
                    "output" to injected.toAbsolutePath(),
                    // TODO logging?
                    "log" to Files.createTempFile("mcinject-log", ".tmp.log").toAbsolutePath(),
                    "exceptions" to exceptions.toAbsolutePath(),
                    "access" to access.toAbsolutePath(),
                    "constructors" to constructors.toAbsolutePath(),
                )
            )

            injected
        }
    })

    private fun accessTransformed() = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val input = if (mcpConfig.official) patched() else injected()
        val accessTransformed = Files.createTempFile("access-transformed-", ".tmp.jar")

        val userdevConfig = this@PatchedSetupState.userdevConfig

        val atFiles = zipFileSystem(userdevConfigFile.toPath()).use { fs ->
            userdevConfig.ats.map {
                val temp = Files.createTempFile("at-", ".tmp.cfg")
                fs.getPath(it).copyTo(temp, StandardCopyOption.REPLACE_EXISTING)

                temp
            }
        }

        override fun description() = BuildOperationDescriptor
            .displayName("Access Transforming $input")
            .progressDisplayName("Making access transformed $accessTransformed")
            .metadata(BuildOperationCategory.TASK)

        @Suppress("UnstableApiUsage")
        override fun call(context: BuildOperationContext) = context.callWithStatus {
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
                    }.toTypedArray()
                )
            } finally {
                System.setOut(out)
                System.setErr(err)
            }

            accessTransformed
        }
    })

    private val withAssets: Path by lazy {
        buildOperationExecutor.call(object : CallableBuildOperation<Path> {
            val accessTransformed = accessTransformed()
            val output = Files.createTempFile("forge-", ".tmp.jar")

            override fun description() = BuildOperationDescriptor
                .displayName("Adding assets to $accessTransformed")
                .progressDisplayName("Output: $output")
                .metadata(BuildOperationCategory.TASK)

            override fun call(context: BuildOperationContext) = context.callWithStatus {
                accessTransformed.copyTo(output, StandardCopyOption.REPLACE_EXISTING)

                zipFileSystem(output).use { outputZip ->
                    zipFileSystem(clientJar.toPath()).use { clientZip ->
                        clientZip.withAssets { path ->
                            val newPath = outputZip.getPath(path.toString())

                            if (newPath.notExists()) {
                                newPath.parent?.createDirectories()
                                path.copyTo(newPath, StandardCopyOption.COPY_ATTRIBUTES)
                            }
                        }
                    }
                }

                val path = cacheManager.fileStoreDirectory.resolve(patchesHash.toString())
                    .resolve(manifest.id)
                    .resolve(checksumService.sha1(output.toFile()).toString())
                    .resolve("forge-${manifest.id}.${ArtifactTypeDefinition.JAR_TYPE}")

                if (path.exists()) {
                    path.deleteExisting()
                } else {
                    path.parent.createDirectories()
                }

                output.copyTo(path)

                path
            }
        })
    }

    val patchedOutput: File by lazy {
        val identifier = DefaultModuleComponentArtifactIdentifier(
            moduleComponentIdentifier,
            moduleComponentIdentifier.module,
            ArtifactTypeDefinition.JAR_TYPE,
            ArtifactTypeDefinition.JAR_TYPE
        )

        val urlId = PatchedArtifactIdentifier(identifier, patchesHash)

        val cache = cacheManager.getMetadataCache(Path("module-artifact"), { PatchedArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile

        val result = DefaultBuildableArtifactResolveResult()
        getOrResolve(DefaultModuleComponentArtifactMetadata(moduleComponentIdentifier, identifier.name), urlId, cache, cachePolicy, timeProvider, result) {
            withAssets.toFile()
        }

        result.result
    }

    private fun executeMcp(
        function: PatchLibrary,
        argumentTemplates: Map<String, Any?>,
        argumentLists: Triple<String, String, Collection<String>>? = null
    ) {
        val jar = functionConfiguration(function).singleFile

        val mainClass = JarFile(jar).use {
            it.manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS)
        }

        val result = processOperations.javaexec { spec ->
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
        private val patchedStates = ConcurrentHashMap<Pair<String, File>, PatchedSetupState>()

        fun getPatchedState(
            moduleComponentIdentifier: PatchedComponentIdentifier,
            manifest: MinecraftVersionMetadata,
            clientJar: File,
            serverJar: File,
            userdevConfigFile: File,
            patchesHash: HashCode,
            cacheManager: CodevCacheManager,
            objectFactory: ObjectFactory
        ) = patchedStates.computeIfAbsent(manifest.id to userdevConfigFile) { (_, userdevFile) ->
            objectFactory.newInstance(PatchedSetupState::class.java, moduleComponentIdentifier, manifest, clientJar, serverJar, userdevFile, patchesHash, cacheManager)
        }

        fun configurationName(libraryName: String): String =
            CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, libraryName.replace(Regex("[:.]"), "-"))
    }
}
