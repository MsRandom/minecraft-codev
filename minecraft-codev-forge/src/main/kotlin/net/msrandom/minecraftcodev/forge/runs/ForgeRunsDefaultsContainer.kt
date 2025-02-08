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
import net.msrandom.minecraftcodev.forge.task.GenerateLegacyClasspath
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.runs.DatagenRunConfigurationData
import net.msrandom.minecraftcodev.runs.MinecraftRunConfiguration
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer.Companion.getManifest
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import java.nio.file.Path
import kotlin.io.path.reader

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
        extractNatives: Provider<ExtractNatives>,
        downloadAssets: Provider<DownloadAssets>,
        data: ForgeRunConfigurationData,
    ) {
        arguments.addAll(
            existing.map {
                if (it.startsWith('{')) {
                    resolveTemplate(
                        manifest,
                        config,
                        it.substring(1, it.length - 1),
                        data,
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
        data: ForgeRunConfigurationData,
    ): Any? =
        when (template) {
            "asset_index" -> manifest.assets
            "assets_root" -> data.downloadAssetsTask.flatMap(DownloadAssets::assetsDirectory)

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
                val files = project.files(data.additionalIncludedSourceSets.flatMap { extra ->
                    sourceSet.map { primary ->
                        (extra + primary).map {
                            if (it.output.resourcesDir == null) {
                                it.output.classesDirs
                            } else {
                                project.files(it.output.resourcesDir, it.output.classesDirs)
                            }
                        }
                    }
                })

                val modClasses = data.modId.map { modId ->
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

            "minecraft_classpath_file" -> data.generateLegacyClasspathTask.flatMap(GenerateLegacyClasspath::output)

            "natives" -> data.extractNativesTask.flatMap(ExtractNatives::destinationDirectory)

            else -> {
                project.logger.warn("Unknown Forge userdev run configuration template $template")
                template
            }
        }

    private fun MinecraftRunConfiguration.addData(
        caller: String,
        data: ForgeRunConfigurationData,
        runType: (UserdevConfig.Runs) -> UserdevConfig.Run?,
    ) {
        val configProvider = getUserdevData(data.patches)

        val getRun: UserdevConfig.() -> UserdevConfig.Run = {
            runType(runs)
                ?: throw UnsupportedOperationException("Attempted to use $caller run configuration which doesn't exist.")
        }

        beforeRun.addAll(configProvider.flatMap {
            val list = project.objects.listProperty(Task::class.java)

            val hasAssets = it.getRun().args.contains("{assets_root}") ||
                    it.getRun().env.containsValue("{assets_root}")

            val hasNatives = it.getRun().env.containsValue("{natives}")

            val hasLegacyClasspath = it.getRun().props.containsValue("{minecraft_classpath_file}")

            if (hasAssets) {
                list.add(data.downloadAssetsTask)
            }

            if (hasNatives) {
                list.add(data.extractNativesTask)
            }

            if (hasLegacyClasspath) {
                list.add(data.generateLegacyClasspathTask)
            }

            list
        })

        val manifestProvider = getManifest(data.minecraftVersion)

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
                                    data,
                                )
                            } else if (value.startsWith('{')) {
                                resolveTemplate(
                                    manifest,
                                    userdevConfig,
                                    value.substring(1, value.length - 1),
                                    data,
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
                    data.extractNativesTask,
                    data.downloadAssetsTask,
                    data,
                )

                val mixinConfigs = project.provider { data.mixinConfigs }.flatMap { compileArguments(it.map { compileArgument("--mixin.config=", it.name) }) }

                compileArguments(arguments).apply {
                    addAll(mixinConfigs)
                }
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
                    data.extractNativesTask,
                    data.downloadAssetsTask,
                    data,
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
                                    data,
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

    fun client(action: Action<ForgeRunConfigurationData>) {
        val data = defaults.configuration.project.objects.newInstance(ForgeRunConfigurationData::class.java)

        action.execute(data)

        defaults.configuration.apply {

            addData(::client.name, data, UserdevConfig.Runs::client)
        }
    }

    fun server(action: Action<ForgeRunConfigurationData>) {
        val data = defaults.configuration.project.objects.newInstance(ForgeRunConfigurationData::class.java)

        action.execute(data)

        defaults.configuration.apply {
            addData(::server.name, data, UserdevConfig.Runs::server)
        }
    }

    fun data(action: Action<ForgeDatagenRunConfigurationData>) {
        val data = defaults.configuration.project.objects.newInstance(ForgeDatagenRunConfigurationData::class.java)

        action.execute(data)

        defaults.configuration.apply {
            val outputDirectory = data.getOutputDirectory(this)

            addData(::data.name, data) { it.data ?: it.serverData }

            val additionalExisting = data.additionalIncludedSourceSets.flatMap {
                compileArguments(it.map {
                    compileArgument("--existing=", it.output.resourcesDir)
                })
            }

            arguments.add(compileArgument("--mod=", data.modId))
            arguments.add("--all")
            arguments.add(compileArgument("--output=", outputDirectory))
            arguments.add(compileArgument("--existing=", sourceSet.map { it.output.resourcesDir!! }))

            arguments.addAll(additionalExisting)
        }
    }

    fun clientData(action: Action<ForgeDatagenRunConfigurationData>) {
        val data = defaults.configuration.project.objects.newInstance(ForgeDatagenRunConfigurationData::class.java)

        action.execute(data)

        defaults.configuration.apply {
            val outputDirectory = data.getOutputDirectory(this)

            addData(::clientData.name, data, UserdevConfig.Runs::clientData)

            val additionalExisting = data.additionalIncludedSourceSets.flatMap {
                compileArguments(it.map {
                    compileArgument("--existing=", it.output.resourcesDir)
                })
            }

            arguments.add(compileArgument("--mod=", data.modId))
            arguments.add("--all")
            arguments.add(compileArgument("--output=", outputDirectory))
            arguments.add(compileArgument("--existing=", sourceSet.map { it.output.resourcesDir!! }))

            arguments.addAll(additionalExisting)
        }
    }

    fun gameTestServer(action: Action<ForgeRunConfigurationData>) {
        val data = defaults.configuration.project.objects.newInstance(ForgeRunConfigurationData::class.java)

        action.execute(data)

        defaults.configuration.apply {
            sourceSet.convention(project.extension<SourceSetContainer>().named(SourceSet.TEST_SOURCE_SET_NAME))

            addData(::gameTestServer.name, data, UserdevConfig.Runs::gameTestServer)
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

    val minecraftVersion: Property<String>
        @Input
        get

    val modId: Property<String>
        @Input
        get

    val additionalIncludedSourceSets: ListProperty<SourceSet>
        @Input
        get

    val extractNativesTask: Property<ExtractNatives>
        @Input
        get

    val downloadAssetsTask: Property<DownloadAssets>
        @Input
        get

    val generateLegacyClasspathTask: Property<GenerateLegacyClasspath>
        @Input
        get
}

abstract class ForgeDatagenRunConfigurationData :
    ForgeRunConfigurationData,
    DatagenRunConfigurationData
