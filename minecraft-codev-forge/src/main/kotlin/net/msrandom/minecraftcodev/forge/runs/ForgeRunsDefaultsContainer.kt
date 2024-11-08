package net.msrandom.minecraftcodev.forge.runs

import kotlinx.coroutines.runBlocking
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
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
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
            val isUserdev = runBlocking {
                MinecraftCodevForgePlugin.userdevConfig(file) {
                    config = it
                }
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
        runtimeClasspath: FileCollection,
        arguments: MutableSet<MinecraftRunConfiguration.Argument>,
        existing: List<String>,
        extractNativesName: String,
        downloadAssetsName: String,
    ) {
        arguments.addAll(
            existing.map {
                if (it.startsWith('{')) {
                    MinecraftRunConfiguration.Argument(
                        resolveTemplate(
                            manifest,
                            config,
                            runtimeClasspath,
                            it.substring(1, it.length - 1),
                            extractNativesName,
                            downloadAssetsName,
                        ),
                    )
                } else {
                    MinecraftRunConfiguration.Argument(it)
                }
            },
        )
    }

    private fun MinecraftRunConfiguration.resolveTemplate(
        manifest: MinecraftVersionMetadata,
        config: UserdevConfig,
        runtimeClasspath: FileCollection,
        template: String,
        extractNativesName: String,
        downloadAssetsName: String,
    ): Any =
        when (template) {
            "asset_index" -> manifest.assets
            "assets_root" -> {
                val task = project.tasks.withType(DownloadAssets::class.java).getByName(downloadAssetsName)

                beforeRun.add(task)

                task.assetsDirectory.asFile
            }

            "modules" -> {
                val configuration = project.configurations.getByName(sourceSet.get().runtimeClasspathConfigurationName)

                val moduleDependencies =
                    config.modules.map {
                        val dependency = project.dependencies.create(it)

                        dependency.group to dependency.name
                    }

                val moduleArtifactView =
                    configuration.incoming.artifactView { viewConfiguration ->
                        viewConfiguration.componentFilter { component ->
                            component is ModuleComponentIdentifier && (component.group to component.module) in moduleDependencies
                        }
                    }

                moduleArtifactView.files.joinToString(File.pathSeparator)
            }

            "MC_VERSION" -> manifest.id
            "mcp_mappings" -> "minecraft-codev.mappings"
            "source_roots" -> {
                val sourceRoots = modClasspaths

                val fixedRoots =
                    if (sourceRoots.isEmpty()) {
                        fun sourceToFiles(sourceSet: SourceSet) =
                            if (sourceSet.output.resourcesDir == null) {
                                sourceSet.output.classesDirs
                            } else {
                                project.files(sourceSet.output.resourcesDir, sourceSet.output.classesDirs)
                            }

                        val sourceSet = sourceSet.get()
                        if (SourceSet.isMain(sourceSet)) {
                            mapOf(null to sourceToFiles(sourceSet))
                        } else {
                            val main =
                                project
                                    .extension<SourceSetContainer>()
                                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME)

                            mapOf(null to sourceToFiles(main) + sourceToFiles(sourceSet))
                        }
                    } else {
                        sourceRoots
                    }

                val modClasses = mutableListOf<Any>()

                for ((modId, files) in fixedRoots) {
                    for (directory in files) {
                        if (modId != null) {
                            modClasses.add(modId)
                            modClasses.add("%%")
                        }

                        modClasses += directory
                        modClasses += File.pathSeparator
                    }
                }

                modClasses.joinToString("")
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

                    runBlocking {
                        zipFileSystem(mappingsArtifact).use {
                            it.getPath("mappings/mappings.tiny").reader().use { reader ->
                                Tiny2Reader.read(reader, mappings)
                            }

                            val sourceNamespace = treeView.getNamespaceId(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE)
                            val targetNamespace = treeView.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

                            for (type in treeView.classes) {
                                val addedClass = srgMappings.addClass(type.getName(targetNamespace), type.getName(sourceNamespace))

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
                path.writeLines(runtimeClasspath.map(File::getAbsolutePath))

                path.toAbsolutePath().toString()
            }

            "natives" -> {
                val task = project.tasks.withType(ExtractNatives::class.java).getByName(extractNativesName)

                beforeRun.add(task)

                task.destinationDirectory.get()
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
        addLwjglNatives: Boolean = false,
    ) {
        val configProvider = getUserdevData(data.patches)

        val extractNativesTaskName = sourceSet.get().extractNativesTaskName
        val downloadAssetsTaskName = sourceSet.get().downloadAssetsTaskName

        val getRun: UserdevConfig.() -> UserdevConfig.Run = {
            runType(runs) ?: throw UnsupportedOperationException("Attempted to use $caller run configuration which doesn't exist.")
        }

        val manifestProvider = getManifest(minecraftVersion)

        mainClass.set(configProvider.map { it.getRun().main })

        val zipped = manifestProvider.zip(configProvider, ::Pair)
        val runtimeClasspath = project.files(sourceSet.map(SourceSet::getRuntimeClasspath))

        environment.putAll(
            zipped.map { (manifest, userdevConfig) ->
                buildMap {
                    for ((key, value) in userdevConfig.getRun().env) {
                        val argument =
                            if (value.startsWith('$')) {
                                MinecraftRunConfiguration.Argument(
                                    resolveTemplate(
                                        manifest,
                                        userdevConfig,
                                        runtimeClasspath,
                                        value.substring(2, value.length - 1),
                                        extractNativesTaskName,
                                        downloadAssetsTaskName,
                                    ),
                                )
                            } else if (value.startsWith('{')) {
                                MinecraftRunConfiguration.Argument(
                                    resolveTemplate(
                                        manifest,
                                        userdevConfig,
                                        runtimeClasspath,
                                        value.substring(1, value.length - 1),
                                        extractNativesTaskName,
                                        downloadAssetsTaskName,
                                    ),
                                )
                            } else {
                                MinecraftRunConfiguration.Argument(value)
                            }

                        put(key, argument)
                    }
                }
            },
        )

        arguments.addAll(
            zipped.map { (manifest, userdevConfig) ->
                val arguments = mutableSetOf<MinecraftRunConfiguration.Argument>()

                addArgs(
                    manifest,
                    userdevConfig,
                    runtimeClasspath,
                    arguments,
                    userdevConfig.getRun().args,
                    extractNativesTaskName,
                    downloadAssetsTaskName,
                )

                for (mixinConfig in data.mixinConfigs) {
                    arguments.add(MinecraftRunConfiguration.Argument("--mixin.config=", mixinConfig.absolutePath))
                }

                arguments
            },
        )

        jvmArguments.addAll(
            zipped.map { (manifest, userdevConfig) ->
                val run = userdevConfig.getRun()
                val jvmArguments = mutableSetOf<MinecraftRunConfiguration.Argument>()

                addArgs(
                    manifest,
                    userdevConfig,
                    runtimeClasspath,
                    jvmArguments,
                    run.jvmArgs,
                    extractNativesTaskName,
                    downloadAssetsTaskName,
                )

                for ((key, value) in run.props) {
                    if (value.startsWith('{')) {
                        val template = value.substring(1, value.length - 1)
                        jvmArguments.add(
                            MinecraftRunConfiguration.Argument(
                                "-D$key=",
                                resolveTemplate(
                                    manifest,
                                    userdevConfig,
                                    runtimeClasspath,
                                    template,
                                    extractNativesTaskName,
                                    downloadAssetsTaskName,
                                ),
                            ),
                        )
                    } else {
                        jvmArguments.add(MinecraftRunConfiguration.Argument("-D$key=$value"))
                    }
                }

                jvmArguments
            },
        )

        if (addLwjglNatives) {
            val natives = project.tasks.withType(ExtractNatives::class.java).getByName(extractNativesTaskName)

            jvmArguments.add(MinecraftRunConfiguration.Argument("-Dorg.lwjgl.librarypath=", natives.destinationDirectory))
        }
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

            addData(::client.name, minecraftVersion, data, UserdevConfig.Runs::client, true)
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

            val outputDirectory = data.getOutputDirectory(this).map(MinecraftRunConfiguration::Argument)

            addData(UserdevConfig.Runs::data.name, minecraftVersion, data, UserdevConfig.Runs::data)

            val resources = sourceSet.map { it.output.resourcesDir!! }

            arguments.add(MinecraftRunConfiguration.Argument("--mod=", data.modId.map(MinecraftRunConfiguration::Argument)))
            arguments.add(MinecraftRunConfiguration.Argument("--all"))
            arguments.add(MinecraftRunConfiguration.Argument("--output=", outputDirectory))
            arguments.add(MinecraftRunConfiguration.Argument("--existing=", outputDirectory))
            arguments.add(MinecraftRunConfiguration.Argument("--existing=", resources))
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

abstract class ForgeRunConfigurationData {
    abstract val patches: ConfigurableFileCollection
        @InputFiles
        get

    abstract val mixinConfigs: ConfigurableFileCollection
        @InputFiles
        get
}

abstract class ForgeDatagenRunConfigurationData :
    ForgeRunConfigurationData(),
    DatagenRunConfigurationData
