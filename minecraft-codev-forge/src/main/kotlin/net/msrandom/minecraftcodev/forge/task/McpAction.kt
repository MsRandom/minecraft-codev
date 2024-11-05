package net.msrandom.minecraftcodev.forge.task

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.Userdev
import net.msrandom.minecraftcodev.forge.mappings.injectForgeMappingService
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.RegularFile
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.process.ExecOperations
import java.io.File
import java.io.OutputStream
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

open class McpAction(
    private val execOperations: ExecOperations,
    private val javaExecutable: File,
    private val jarFile: File,
    private val mcpConfig: McpConfigFile,
    private val args: List<String>,
    private val argumentTemplates: Map<String, Any>,
    private val stdout: OutputStream?,
) {
    open val inputName = "input"

    suspend fun execute(fileSystem: FileSystem, input: Path) = execute(fileSystem, mapOf(inputName to input))

    suspend fun execute(fileSystem: FileSystem, vararg inputs: Pair<String, Path>) = execute(fileSystem, mapOf(*inputs))

    protected open suspend fun execute(fileSystem: FileSystem, inputs: Map<String, Path> = emptyMap()): Path {
        val output = withContext(Dispatchers.IO) {
            Files.createTempFile("mcp-step", ".out")
        }

        val executionResult =
            execOperations.javaexec {
                it.executable(javaExecutable)

                val mainClass =
                    jarFile.let(::JarFile)
                        .use { jar ->
                            jar
                                .getInputStream(jar.getJarEntry(JarFile.MANIFEST_NAME))
                                .use(::Manifest)
                                .mainAttributes
                                .getValue(Attributes.Name.MAIN_CLASS)
                        }

                it.classpath(jarFile)
                it.mainClass.set(mainClass)

                val args =
                    args.map { arg ->
                        if (!arg.startsWith('{')) {
                            return@map arg
                        }

                        val template = arg.subSequence(1, arg.length - 1)

                        val templateReplacement =
                            output.takeIf { template == "output" }
                                ?: inputs[template]
                                ?: argumentTemplates[template]
                                ?: mcpConfig.config.data[template]?.let {
                                    val dataOutput = Files.createTempFile("mcp-data", template.toString())

                                    fileSystem.getPath(
                                        it,
                                    ).copyTo(
                                        dataOutput,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.COPY_ATTRIBUTES,
                                    )

                                    dataOutput
                                }
                                ?: throw UnsupportedOperationException("Unknown argument for MCP function $args: $template")

                        when (templateReplacement) {
                            is RegularFile -> templateReplacement.toString()
                            is Path -> templateReplacement.toAbsolutePath().toString()
                            is File -> templateReplacement.absolutePath
                            else -> templateReplacement.toString()
                        }
                    }

                it.args = args

                it.standardOutput = stdout ?: it.standardOutput
            }

        executionResult.rethrowFailure()
        executionResult.assertNormalExitValue()

        return output
    }
}

class PatchMcpAction(
    execOperations: ExecOperations,
    javaExecutable: File,
    mcpConfig: McpConfigFile,
    private val userdev: Userdev,
    logFile: OutputStream,
    private val configurationContainer: ConfigurationContainer,
    private val dependencyHandler: DependencyHandler,
) : McpAction(
    execOperations,
    javaExecutable,
    resolveFile(configurationContainer, dependencyHandler, userdev.config.binpatcher.version),
    mcpConfig,
    userdev.config.binpatcher.args,
    emptyMap(),
    logFile,
) {
    override val inputName
        get() = "clean"

    override suspend fun execute(fileSystem: FileSystem, inputs: Map<String, Path>): Path {
        val userdevConfig = userdev.config

        val patched =
            run {
                val patches = Files.createTempFile("patches", "lzma")

                zipFileSystem(userdev.source.toPath()).use {
                    it.getPath(userdevConfig.binpatches).copyTo(patches, StandardCopyOption.REPLACE_EXISTING)
                }

                super.execute(fileSystem, inputs + mapOf("patch" to patches))
            }

        val input = inputs.getValue("clean")

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

            val universal = resolveFile(configurationContainer, dependencyHandler, userdevConfig.universal)

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
            val inject = userdevConfig.inject

            if (inject == null) {
                injectForgeMappingService(patchedZip)

                return patched
            }

            zipFileSystem(userdev.source.toPath()).use userdev@{ userdevZip ->
                val injectPath = userdevZip.getPath("/$inject")

                if (injectPath.notExists()) {
                    return@userdev
                }

                injectPath.walk {
                    for (path in filter(Path::isRegularFile)) {
                        val output = patchedZip.getPath(injectPath.relativize(path).toString())

                        output.parent?.createDirectories()
                        path.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }

            injectForgeMappingService(patchedZip)
        }

        return patched
    }
}
