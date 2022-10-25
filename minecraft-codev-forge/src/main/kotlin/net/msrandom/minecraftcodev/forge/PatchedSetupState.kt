package net.msrandom.minecraftcodev.forge

import com.google.common.io.ByteStreams.nullOutputStream
import kotlinx.serialization.json.decodeFromStream
import net.minecraftforge.accesstransformer.TransformerProcessor
import net.minecraftforge.srgutils.IMappingFile
import net.minecraftforge.srgutils.INamedMappingFile
import net.msrandom.minecraftcodev.core.LegacyJarSplitter.useFileSystems
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension.Companion.json
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.walk
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.core.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.repository.JarSplittingResult
import net.msrandom.minecraftcodev.core.repository.getExtractionState
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.repository.*
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.ProcessOperations
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.hash.HashCode
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.CallableBuildOperation
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
    private val manifest: MinecraftVersionMetadata,
    private val clientJar: File,
    private val serverJar: File,
    private val userdevConfigFile: File,
    private val patchesHash: HashCode,
    private val cacheManager: CodevCacheManager,

    private val buildOperationExecutor: BuildOperationExecutor,
    private val processOperations: ProcessOperations,
    private val checksumService: ChecksumService,
    private val project: Project
) {
    private val extractionState = getExtractionState(buildOperationExecutor, manifest, serverJar) { clientJar }

    private val userdevConfig by lazy {
        zipFileSystem(userdevConfigFile.toPath()).use { fs ->
            fs.getPath("config.json").inputStream().use { json.decodeFromStream<UserdevConfig>(it) }
        }
    }

    private val mcpConfigFile by lazy {
        project.unsafeResolveConfiguration(project.configurations.detachedConfiguration(project.dependencies.create(userdevConfig.mcp))).singleFile.toPath()
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

    private fun merged(): Path = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val merged = Files.createTempFile("merged-", ".tmp.jar")

        override fun description() = BuildOperationDescriptor
            .displayName("Merging ${clientJar.toPath()} and ${extractionState.value.result}")
            .progressDisplayName("Merging to $merged")
            .details(JarMergeOperationDetails(clientJar.toPath(), extractionState.value.result, merged))

        override fun call(context: BuildOperationContext): Path {
            executeMcp(
                mcpConfig.functions.getValue("merge"),
                mapOf(
                    "client" to clientJar.absolutePath,
                    "server" to extractionState.value.result.toAbsolutePath(),
                    "version" to manifest.id,
                    "output" to merged.toAbsolutePath()
                )
            )

            return merged
        }
    })

    private fun patched() = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val input = if (userdevConfig.notchObf) merged() else this@PatchedSetupState.renamed()
        val patched = Files.createTempFile("patched-", ".tmp.jar")
        val patchesPath = Files.createTempFile("patches-", ".tmp.lzma")

        override fun description() = BuildOperationDescriptor
            .displayName("Patching $input")
            .progressDisplayName("Applying binary patches")
            .details(JarPatchOperationDetails(input, patched, patchesPath))

        override fun call(context: BuildOperationContext): Path {
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

            // Add Forge files
            val universal = project.unsafeResolveConfiguration(project.configurations.detachedConfiguration(project.dependencies.create(userdevConfig.universal)).apply {
                isTransitive = false
            }).singleFile

            zipFileSystem(patched).use { patchedZip ->
                // Add missing non-patched files
                zipFileSystem(input).use { inputZip ->
                    inputZip.getPath("/").walk {
                        for (path in filter(Path::isRegularFile)) {
                            val output = patchedZip.getPath(path.toString())

                            if (output.notExists()) {
                                output.parent?.createDirectories()
                                path.copyTo(output)
                            }
                        }
                    }
                }

                // Add files from the universal Jar
                zipFileSystem(universal.toPath()).use { universalZip ->
                    universalZip.getPath("/").walk {
                        for (path in filter(Path::isRegularFile)) {
                            val output = patchedZip.getPath(path.toString())

                            output.parent?.createDirectories()
                            path.copyTo(output)
                        }
                    }
                }

                // Add userdev injects
                userdevConfig.inject?.let { inject ->
                    zipFileSystem(userdevConfigFile.toPath()).use { userdevZip ->
                        if (userdevZip.getPath(inject).exists()) {
                            userdevZip.getPath(inject).walk {
                                for (path in filter(Path::isRegularFile)) {
                                    val output = patchedZip.getPath(path.toString())

                                    output.parent?.createDirectories()
                                    path.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }

            return patched
        }
    })

    private fun renamed(): Path = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val extractionState = getExtractionState(buildOperationExecutor, manifest, serverJar) { clientJar }
        val input = if (userdevConfig.notchObf) patched() else merged()
        val renamed = Files.createTempFile("renamed-", ".tmp.jar")
        val fixedMappingsPath = Files.createTempFile("mappings-", ".tmp.tsrg")

        override fun description() = BuildOperationDescriptor
            .displayName("Renaming $input from ${MappingsNamespace.OBF} to ${MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE}")
            .progressDisplayName("Renaming to $renamed")
            .details(JarRenameOperationDetails(input, renamed, fixedMappingsPath))

        override fun call(context: BuildOperationContext): Path {
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
                mcpConfig.functions.getValue("rename"),
                mapOf(
                    "input" to input.toAbsolutePath(),
                    "output" to renamed.toAbsolutePath(),
                    "mappings" to fixedMappingsPath.toAbsolutePath()
                ),
                Triple(
                    "libraries",
                    "-e",
                    project.unsafeResolveConfiguration(
                        project.configurations.detachedConfiguration(
                            *extractionState.value
                                .libraries
                                .map { project.dependencies.create(it.toString()) }
                                .toTypedArray()
                        )
                    ).map(File::toString)
                )
            )

            return renamed
        }
    })

    private fun injected() = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
        val input = if (userdevConfig.notchObf) renamed() else patched()
        val injected = Files.createTempFile("injected-", ".tmp.jar")

        override fun description() = BuildOperationDescriptor
            .displayName("Injecting into $input")
            .progressDisplayName("Apply Minecraft Injections as $injected")
            .details(JarInjectOperationDetails(input, injected, userdevConfigFile.toPath()))

        override fun call(context: BuildOperationContext): Path {
            val access = Files.createTempFile("access-", ".tmp.txt")
            val constructors = Files.createTempFile("constructors-", ".tmp.txt")
            val exceptions = Files.createTempFile("exceptions-", ".tmp.txt")

            zipFileSystem(mcpConfigFile).use {
                it.getPath(mcpConfig.data.access!!).copyTo(access, StandardCopyOption.REPLACE_EXISTING)
                it.getPath(mcpConfig.data.constructors!!).copyTo(constructors, StandardCopyOption.REPLACE_EXISTING)
                it.getPath(mcpConfig.data.exceptions!!).copyTo(exceptions, StandardCopyOption.REPLACE_EXISTING)
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
                )
            )

            return injected
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
            .details(JarAccessTransformOperationDetails(input, accessTransformed, atFiles))

        @Suppress("UnstableApiUsage")
        override fun call(context: BuildOperationContext): Path {
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

            return accessTransformed
        }
    })

    val split: JarSplittingResult by lazy {
        buildOperationExecutor.call(object : CallableBuildOperation<JarSplittingResult> {
            val accessTransformed = accessTransformed()
            val common = Files.createTempFile("split-common-", ".tmp.jar")
            val client = Files.createTempFile("split-client-", ".tmp.jar")

            override fun description() = BuildOperationDescriptor
                .displayName("Splitting $accessTransformed")
                .progressDisplayName("Common: $common, Client: $client")
                .details(MergedJarSplitOperationDetails(accessTransformed, common, client))

            override fun call(context: BuildOperationContext): JarSplittingResult {
                common.deleteExisting()
                client.deleteExisting()

                useFileSystems { handle ->
                    val mergedFs = zipFileSystem(accessTransformed).also(handle)
                    val obfClientFs = zipFileSystem(clientJar.toPath()).also(handle)
                    val obfServerFs = zipFileSystem(serverJar.toPath()).also(handle)
                    val commonFs = zipFileSystem(common, true).also(handle)
                    val clientFs = zipFileSystem(client, true).also(handle)
                    val mappings = mappings
                    ForgeJarSplitter.splitJars(mergedFs, obfClientFs, obfServerFs, commonFs, clientFs, mappings.getMap(mappings.names[0], mappings.names[1]).reverse())
                }

                val commonPath = cacheManager.fileStoreDirectory.resolve(patchesHash.toString())
                    .resolve(MinecraftComponentResolvers.COMMON_MODULE)
                    .resolve(manifest.id)
                    .resolve(checksumService.sha1(common.toFile()).toString())
                    .resolve("${MinecraftComponentResolvers.COMMON_MODULE}-${manifest.id}-patched.${ArtifactTypeDefinition.JAR_TYPE}")

                val clientPath = cacheManager.fileStoreDirectory.resolve(patchesHash.toString())
                    .resolve(MinecraftComponentResolvers.CLIENT_MODULE)
                    .resolve(manifest.id)
                    .resolve(checksumService.sha1(client.toFile()).toString())
                    .resolve("${MinecraftComponentResolvers.CLIENT_MODULE}-${manifest.id}-patched.${ArtifactTypeDefinition.JAR_TYPE}")

                if (commonPath.exists()) {
                    commonPath.deleteExisting()
                } else {
                    commonPath.parent.createDirectories()
                }

                if (clientPath.exists()) {
                    clientPath.deleteExisting()
                } else {
                    clientPath.parent.createDirectories()
                }

                common.copyTo(commonPath)
                client.copyTo(clientPath)

                return JarSplittingResult(commonPath, clientPath)
            }
        })
    }

    private fun executeMcp(
        function: PatchLibrary,
        argumentTemplates: Map<String, Any?>,
        argumentLists: Triple<String, String, Collection<String>>? = null
    ) {
        val jar = project.unsafeResolveConfiguration(project.configurations.detachedConfiguration(project.dependencies.create(function.version)).apply {
            isTransitive = false
        }).singleFile

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
            manifest: MinecraftVersionMetadata,
            clientJar: File,
            serverJar: File,
            userdevConfigFile: File,
            patchesHash: HashCode,
            cacheManager: CodevCacheManager,
            objectFactory: ObjectFactory
        ) = patchedStates.computeIfAbsent(manifest.id to userdevConfigFile) { (_, userdevFile) ->
            objectFactory.newInstance(PatchedSetupState::class.java, manifest, clientJar, serverJar, userdevFile, patchesHash, cacheManager)
        }
    }
}
