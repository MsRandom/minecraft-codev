package net.msrandom.minecraftcodev.forge.task

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.PatchLibrary
import net.msrandom.minecraftcodev.forge.Userdev
import net.msrandom.minecraftcodev.forge.mappings.injectForgeMappingService
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File
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
    protected val project: Project,
    private val metadata: MinecraftVersionMetadata,
    private val mcpConfig: McpConfigFile,
    private val library: PatchLibrary,
    private val argumentTemplates: Map<String, Any>,
) {
    open val inputName = "input"

    fun execute(input: Path) = execute(mapOf(inputName to input))

    fun execute(vararg inputs: Pair<String, Path>) = execute(mapOf(*inputs))

    protected open fun execute(inputs: Map<String, Path> = emptyMap()): Path {
        val output = Files.createTempFile("mcp-step", ".out")

        val executionResult =
            project.javaexec {
                val executable =
                    project
                        .extension<JavaToolchainService>()
                        .launcherFor { it.languageVersion.set(JavaLanguageVersion.of(metadata.javaVersion.majorVersion)) }
                        .get()
                        .executablePath

                it.executable(executable)

                val jarFile =
                    project.configurations
                        .detachedConfiguration(project.dependencies.create(library.version))
                        .apply { isTransitive = false }
                        .singleFile

                val mainClass =
                    jarFile.let(::JarFile)
                        .use { jar ->
                            jar
                                .getInputStream(jar.getJarEntry("META-INF/MANIFEST.MF"))
                                .use(::Manifest)
                                .mainAttributes
                                .getValue(Attributes.Name.MAIN_CLASS)
                        }

                it.classpath(jarFile)
                it.mainClass.set(mainClass)

                val args =
                    zipFileSystem(mcpConfig.source.toPath()).use { (fs) ->
                        library.args.map { arg ->
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

                                        fs.getPath(
                                            it,
                                        ).copyTo(
                                            dataOutput,
                                            StandardCopyOption.REPLACE_EXISTING,
                                            StandardCopyOption.COPY_ATTRIBUTES,
                                        )

                                        dataOutput
                                    }
                                    ?: throw UnsupportedOperationException("Unknown argument for MCP function ${library.args}: $template")

                            when (templateReplacement) {
                                is RegularFile -> templateReplacement.toString()
                                is Path -> templateReplacement.toAbsolutePath().toString()
                                is File -> templateReplacement.absolutePath
                                else -> templateReplacement.toString()
                            }
                        }
                    }

                it.args = args
            }

        executionResult.rethrowFailure()
        executionResult.assertNormalExitValue()

        return output
    }
}

class PatchMcpAction(project: Project, metadata: MinecraftVersionMetadata, mcpConfig: McpConfigFile, private val userdev: Userdev) : McpAction(
    project,
    metadata,
    mcpConfig,
    userdev.config.binpatcher,
    emptyMap(),
) {
    override val inputName
        get() = "clean"

    override fun execute(inputs: Map<String, Path>): Path {
        val userdevConfig = userdev.config

        val patched =
            run {
                val patches = Files.createTempFile("patches", "lzma")

                zipFileSystem(userdev.source.toPath()).use {
                    it.base.getPath(userdevConfig.binpatches).copyTo(patches, StandardCopyOption.REPLACE_EXISTING)
                }

                super.execute(inputs + mapOf("patch" to patches))
            }

        val input = inputs.getValue("clean")

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

            val universal =
                project.configurations.detachedConfiguration(project.dependencies.create(userdevConfig.universal)).apply {
                    isTransitive = false
                }.singleFile

            val filters = userdevConfig.universalFilters.map(::Regex)

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
            val inject = userdevConfig.inject

            if (inject == null) {
                injectForgeMappingService(patched)

                return patched
            }

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

        injectForgeMappingService(patched)

        return patched
    }
}
