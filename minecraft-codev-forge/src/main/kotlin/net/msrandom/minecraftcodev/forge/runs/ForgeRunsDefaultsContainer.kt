package net.msrandom.minecraftcodev.forge.runs

import net.fabricmc.mappingio.format.Tiny2Reader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.minecraftforge.srgutils.IMappingBuilder
import net.minecraftforge.srgutils.IMappingFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.forge.patchesConfigurationName
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.runs.*
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer.Companion.getManifest
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.gradle.api.Action
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.reader
import kotlin.io.path.writeLines

open class ForgeRunsDefaultsContainer(
    private val defaults: RunConfigurationDefaultsContainer,
) {
    private fun loadUserdev(patches: FileCollection): UserdevConfig {
        var config: UserdevConfig? = null

        for (file in patches) {
            val isUserdev = MinecraftCodevForgePlugin.userdevConfig(file) {
                config = it
            }

            if (isUserdev) break
        }

        return config ?: throw UnsupportedOperationException("Patches $patches did not contain Forge userdev.")
    }

    private fun MinecraftRunConfiguration.getUserdevData(patches: FileCollection): Provider<UserdevConfig> {
        if (patches.isEmpty) {
            val configuration = sourceSet.flatMap { project.configurations.named(it.patchesConfigurationName) }

            return configuration.map(::loadUserdev)
        }

        return project.provider {
            loadUserdev(patches)
        }
    }

    private fun MinecraftRunConfiguration.addArgs(
        manifest: MinecraftVersionMetadata,
        config: UserdevConfig,
        arguments: MutableList<Any?>,
        existing: List<String>,
        extractNativesName: String,
        downloadAssetsName: String,
        modId: Provider<String>,
    ) {
        arguments.addAll(
            existing.map {
                if (it.startsWith('{')) {
                    resolveTemplate(
                        manifest,
                        config,
                        it.substring(1, it.length - 1),
                        extractNativesName,
                        downloadAssetsName,
                        modId,
                    )
                } else {
                    it
                }
            },
        )
    }

    private fun MinecraftRunConfiguration.resolveTemplate(
        manifest: MinecraftVersionMetadata,
        config: UserdevConfig,
        template: String,
        extractNativesName: String,
        downloadAssetsName: String,
        modId: Provider<String>,
    ): Any? =
        when (template) {
            "asset_index" -> manifest.assets
            "assets_root" -> {
                val task = project.tasks.named(downloadAssetsName, DownloadAssets::class.java)

                beforeRun.add(task)

                task.flatMap(DownloadAssets::assetsDirectory)
            }

            "modules" -> {
                val configuration = sourceSet.flatMap {
                    project.configurations.named(it.runtimeClasspathConfigurationName)
                }

                val moduleDependencies =
                    config.modules.map {
                        val dependency = project.dependencies.create(it)

                        dependency.group to dependency.name
                    }

                val moduleArtifactView =
                    configuration.map {
                        it.incoming.artifactView { viewConfiguration ->
                            viewConfiguration.componentFilter { component ->
                                component is ModuleComponentIdentifier && (component.group to component.module) in moduleDependencies
                            }
                        }
                    }

                moduleArtifactView.map { it.files.joinToString(File.pathSeparator) }
            }

            "MC_VERSION" -> manifest.id
            "mcp_mappings" -> "minecraft-codev.mappings"
            "source_roots" -> {
                val files = sourceSet.map {
                    if (it.output.resourcesDir == null) {
                        it.output.classesDirs
                    } else {
                        project.files(it.output.resourcesDir, it.output.classesDirs)
                    }
                }

                val modClasses = files.zip(modId, ::Pair).map { (files, modId) ->
                    files.joinToString(File.pathSeparator) {
                        "$modId%%$it"
                    }
                }

                modClasses
            }

            "mcp_to_srg" -> {
                val srgMappings = IMappingBuilder.create()

                val mappingsArtifact: Path? = null
                /*runtimeConfiguration.resolvedConfiguration
                    .resolvedArtifacts
                    .firstOrNull {
                        it.moduleVersion.id.group == FmlLoaderWrappedComponentIdentifier.MINECRAFT_FORGE_GROUP &&
                            it.moduleVersion.id.name == "forge" &&
                            it.classifier == "mappings" &&
                            it.extension == ArtifactTypeDefinition.ZIP_TYPE
                    }
                    ?.file
                    ?.toPath()*/

                if (mappingsArtifact != null) {
                    val mappings = MemoryMappingTree()

                    // Compiler doesn't like working with MemoryMappingTree for some reason
                    val treeView: MappingTreeView = mappings

                    zipFileSystem(mappingsArtifact).use {
                        it.getPath("mappings/mappings.tiny").reader().use { reader ->
                            Tiny2Reader.read(reader, mappings)
                        }

                        val sourceNamespace = treeView.getNamespaceId(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE)
                        val targetNamespace =
                            treeView.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

                        for (type in treeView.classes) {
                            val addedClass =
                                srgMappings.addClass(type.getName(targetNamespace), type.getName(sourceNamespace))

                            for (field in type.fields) {
                                addedClass.field(field.getName(sourceNamespace), field.getName(targetNamespace))
                            }

                            for (method in type.methods) {
                                addedClass.method(
                                    method.getDesc(sourceNamespace),
                                    method.getName(sourceNamespace),
                                    method.getName(targetNamespace),
                                )
                            }
                        }
                    }
                }

                val path =
                    project.layout.buildDirectory
                        .dir("mcpToSrg")
                        .get()
                        .file("mcp.srg")
                        .toPath()

                srgMappings.build().write(path, IMappingFile.Format.SRG)
                path
            }

            "minecraft_classpath_file" -> {
                val path =
                    project.layout.buildDirectory
                        .dir("legacyClasspath")
                        .get()
                        .file("${sourceSet.get().name}.txt")
                        .toPath()

                path.parent.createDirectories()

                // TODO Works for neoforge, for forge it should be ${sourceSet.get().runtimeClasspath - sourceSet.get().output}
                val runtimeClasspath = sourceSet.flatMap {
                    project.configurations.named(it.runtimeClasspathConfigurationName)
                }

                runtimeClasspath.map {
                    // TODO This is making an assumption that the forge Jar is not here
                    val files = it.map(File::getAbsolutePath)

                    path.writeLines(files)

                    path.toAbsolutePath().toString()
                }
            }

            "natives" -> {
                val task = project.tasks.named(extractNativesName, ExtractNatives::class.java)

                beforeRun.add(task)

                task.flatMap(ExtractNatives::destinationDirectory)
            }

            else -> {
                project.logger.warn("Unknown Forge userdev run configuration template $template")
                template
            }
        }

    private fun MinecraftRunConfiguration.addData(
        caller: String,
        minecraftVersion: Provider<String>,
        data: ForgeRunConfigurationData,
        runType: (UserdevConfig.Runs) -> UserdevConfig.Run?,
    ) {
        val configProvider = getUserdevData(data.patches)

        val extractNativesTaskName = sourceSet.get().extractNativesTaskName
        val downloadAssetsTaskName = sourceSet.get().downloadAssetsTaskName

        val getRun: UserdevConfig.() -> UserdevConfig.Run = {
            runType(runs)
                ?: throw UnsupportedOperationException("Attempted to use $caller run configuration which doesn't exist.")
        }

        val manifestProvider = getManifest(minecraftVersion)

        mainClass.set(configProvider.map { it.getRun().main })

        val zipped = manifestProvider.zip(configProvider, ::Pair)

        environment.putAll(
            zipped.flatMap { (manifest, userdevConfig) ->
                project.objects.mapProperty(String::class.java, String::class.java).apply {
                    for ((key, value) in userdevConfig.getRun().env) {
                        val argument =
                            if (value.startsWith('$')) {
                                resolveTemplate(
                                    manifest,
                                    userdevConfig,
                                    value.substring(2, value.length - 1),
                                    extractNativesTaskName,
                                    downloadAssetsTaskName,
                                    data.modId,
                                )
                            } else if (value.startsWith('{')) {
                                resolveTemplate(
                                    manifest,
                                    userdevConfig,
                                    value.substring(1, value.length - 1),
                                    extractNativesTaskName,
                                    downloadAssetsTaskName,
                                    data.modId,
                                )
                            } else {
                                value
                            }

                        put(key, compileArgument(argument))
                    }
                }
            },
        )

        arguments.addAll(
            zipped.flatMap { (manifest, userdevConfig) ->
                val arguments = mutableListOf<Any?>()

                addArgs(
                    manifest,
                    userdevConfig,
                    arguments,
                    userdevConfig.getRun().args,
                    extractNativesTaskName,
                    downloadAssetsTaskName,
                    data.modId,
                )

                for (mixinConfig in data.mixinConfigs) {
                    arguments.add(compileArgument("--mixin.config=", mixinConfig.absolutePath))
                }

                compileArguments(arguments)
            },
        )

        jvmArguments.addAll(
            zipped.flatMap { (manifest, userdevConfig) ->
                val run = userdevConfig.getRun()
                val jvmArguments = mutableListOf<Any?>()

                addArgs(
                    manifest,
                    userdevConfig,
                    jvmArguments,
                    run.jvmArgs,
                    extractNativesTaskName,
                    downloadAssetsTaskName,
                    data.modId,
                )

                for ((key, value) in run.props) {
                    if (value.startsWith('{')) {
                        val template = value.substring(1, value.length - 1)
                        jvmArguments.add(
                            compileArgument(
                                "-D$key=",
                                resolveTemplate(
                                    manifest,
                                    userdevConfig,
                                    template,
                                    extractNativesTaskName,
                                    downloadAssetsTaskName,
                                    data.modId,
                                ),
                            ),
                        )
                    } else {
                        jvmArguments.add("-D$key=$value")
                    }
                }

                compileArguments(jvmArguments)
            },
        )
    }

    fun client(
        minecraftVersion: Provider<String>,
        action: Action<ForgeRunConfigurationData>? = null,
    ): Unit =
        defaults.builder.action {
            val data =
                defaults.builder.project.objects
                    .newInstance(ForgeRunConfigurationData::class.java)

            action?.execute(data)

            addData(::client.name, minecraftVersion, data, UserdevConfig.Runs::client)
        }

    fun server(
        minecraftVersion: Provider<String>,
        action: Action<ForgeRunConfigurationData>? = null,
    ): Unit =
        defaults.builder.action {
            val data =
                defaults.builder.project.objects
                    .newInstance(ForgeRunConfigurationData::class.java)

            action?.execute(data)

            addData(::server.name, minecraftVersion, data, UserdevConfig.Runs::server)
        }

    fun data(
        minecraftVersion: Provider<String>,
        action: Action<ForgeDatagenRunConfigurationData>,
    ) {
        defaults.builder.action {
            val data =
                defaults.builder.project.objects
                    .newInstance(ForgeDatagenRunConfigurationData::class.java)

            action.execute(data)

            val outputDirectory = data.getOutputDirectory(this)

            addData(UserdevConfig.Runs::data.name, minecraftVersion, data, UserdevConfig.Runs::data)

            val resources = sourceSet.map { it.output.resourcesDir!! }

            arguments.add(compileArgument("--mod=", data.modId))
            arguments.add("--all")
            arguments.add(compileArgument("--output=", outputDirectory))
            arguments.add(compileArgument("--existing=", outputDirectory))
            arguments.add(compileArgument("--existing=", resources))
        }
    }

    fun gameTestServer(
        minecraftVersion: Provider<String>,
        action: Action<ForgeRunConfigurationData>? = null,
    ) {
        defaults.builder.setup {
            sourceSet.convention(project.extension<SourceSetContainer>().named(SourceSet.TEST_SOURCE_SET_NAME))
        }

        defaults.builder.action {
            val data =
                defaults.builder.project.objects
                    .newInstance(ForgeRunConfigurationData::class.java)

            action?.execute(data)

            addData(::gameTestServer.name, minecraftVersion, data, UserdevConfig.Runs::gameTestServer)
        }
    }
}

interface ForgeRunConfigurationData {
    val patches: ConfigurableFileCollection
        @InputFiles
        get

    val mixinConfigs: ConfigurableFileCollection
        @InputFiles
        get

    val modId: Property<String>
        @Input
        get
}

abstract class ForgeDatagenRunConfigurationData :
    ForgeRunConfigurationData,
    DatagenRunConfigurationData
