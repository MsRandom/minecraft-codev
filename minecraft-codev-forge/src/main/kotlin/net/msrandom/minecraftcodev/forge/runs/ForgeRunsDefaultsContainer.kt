package net.msrandom.minecraftcodev.forge.runs

import net.fabricmc.mappingio.format.Tiny2Reader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.minecraftforge.srgutils.IMappingBuilder
import net.minecraftforge.srgutils.IMappingFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.runs.AssetDownloader
import net.msrandom.minecraftcodev.runs.MinecraftCodevRunsPlugin
import net.msrandom.minecraftcodev.runs.MinecraftRunConfiguration
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer.Companion.findMinecraft
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer.Companion.getConfiguration
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer.Companion.getManifest
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.apache.commons.lang3.StringUtils
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File
import kotlin.io.path.reader

open class ForgeRunsDefaultsContainer(private val defaults: RunConfigurationDefaultsContainer) {
    private fun MinecraftRunConfiguration.getUserdevData() = sourceSet.flatMap {
        @Suppress("UnstableApiUsage")
        val name = if (SourceSet.isMain(it)) {
            MinecraftCodevForgePlugin.PATCHES_CONFIGURATION
        } else {
            it.name + StringUtils.capitalize(MinecraftCodevForgePlugin.PATCHES_CONFIGURATION)
        }

        project.configurations.named(name)
    }.map { patches ->
        var config: UserdevConfig? = null
        for (file in patches) {
            val isUserdev = MinecraftCodevForgePlugin.userdevConfig(file) {
                config = it
            }

            if (isUserdev) break
        }

        config ?: throw UnsupportedOperationException("Patches configuration $patches did not contain Forge userdev.")
    }

    private fun MinecraftRunConfiguration.addArgs(
        manifest: MinecraftVersionMetadata,
        config: UserdevConfig,
        arguments: MutableSet<MinecraftRunConfiguration.Argument>,
        existing: List<String>,
        extractNativesName: String
    ) {
        arguments.addAll(
            existing.map {
                if (it.startsWith('{')) {
                    MinecraftRunConfiguration.Argument(resolveTemplate(manifest, config, it.substring(1, it.length - 1), extractNativesName))
                } else {
                    MinecraftRunConfiguration.Argument(it)
                }
            }
        )
    }

    private fun MinecraftRunConfiguration.resolveTemplate(manifest: MinecraftVersionMetadata, config: UserdevConfig, template: String, extractNativesName: String): Any {
        return when (template) {
            "asset_index" -> manifest.assets
            "assets_root" -> {
                AssetDownloader.downloadAssets(project, manifest.assetIndex)
                project.plugins.getPlugin(MinecraftCodevRunsPlugin::class.java).assets
            }

            "modules" -> config.modules.flatMapTo(mutableSetOf()) {
                project.configurations.detachedConfiguration(project.dependencies.create(it)).setTransitive(false)
            }.joinToString(File.pathSeparator)

            "MC_VERSION" -> manifest.id
            "mcp_mappings" -> "minecraft-codev.mappings"
            "source_roots" -> {
                val sourceRoots = modClasspaths

                val fixedRoots = if (sourceRoots.isEmpty()) {
                    val main = project.extensions.getByType(SourceSetContainer::class.java).getByName(SourceSet.MAIN_SOURCE_SET_NAME)

                    fun sourceToFiles(sourceSet: SourceSet) = if (sourceSet.output.resourcesDir == null) {
                        sourceSet.output.classesDirs
                    } else {
                        project.files(sourceSet.output.resourcesDir, sourceSet.output.classesDirs)
                    }

                    if (sourceSet.get() == main) {
                        mapOf(null to sourceToFiles(main))
                    } else {
                        mapOf(null to sourceToFiles(main) + sourceToFiles(sourceSet.get()))
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

                val mappingsArtifact = project.configurations.getByName(sourceSet.get().runtimeClasspathConfigurationName)
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .firstOrNull {
                        it.moduleVersion.id.group == MinecraftComponentResolvers.GROUP &&
                                it.moduleVersion.id.name == "forge" &&
                                it.name == "mappings" &&
                                it.extension == ArtifactTypeDefinition.ZIP_TYPE
                    }
                    ?.file
                    ?.toPath()

                if (mappingsArtifact != null) {
                    val mappings = MemoryMappingTree()

                    // Compiler doesn't like working with MemoryMappingTree for some reason
                    val treeView: MappingTreeView = mappings

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
                                addedClass.method(method.getDesc(sourceNamespace), method.getName(sourceNamespace), method.getName(targetNamespace))
                            }
                        }
                    }
                }

                val path = project.layout.buildDirectory.dir("mcpToSrg").get().file("mcp.srg").asFile.toPath()
                srgMappings.build().write(path, IMappingFile.Format.SRG)
                path
            }

            "natives" -> project.tasks.withType(ExtractNatives::class.java).getByName(extractNativesName).destinationDirectory.get()
            else -> {
                project.logger.warn("Unknown Forge userdev run configuration template $template")
                template
            }
        }
    }

    private fun MinecraftRunConfiguration.addData(caller: String, runType: (UserdevConfig.Runs) -> UserdevConfig.Run?, hasNatives: Boolean) {
        val configProvider = getUserdevData()

        @Suppress("UnstableApiUsage")
        val sourceSetName = sourceSet.get().takeUnless(SourceSet::isMain)?.name?.let(StringUtils::capitalize).orEmpty()
        val taskName = "extract${sourceSetName}Natives"

        if (hasNatives) {
            beforeRunTasks.add(taskName)
        }

        val runProvider = configProvider.map {
            runType(it.runs) ?: throw UnsupportedOperationException("Attempted to get use run configuration which doesn't exist.")
        }

        val configuration = getConfiguration()
        val artifact = configuration.findMinecraft("forge", caller)
        val manifestProvider = getManifest(configuration, artifact)

        mainClass.set(runProvider.map(UserdevConfig.Run::main))

        val zipped = runProvider
            .zip(manifestProvider) { run, manifest -> run to manifest }
            .zip(configProvider) { (run, manifest), config -> Triple(run, manifest, config) }

        environment.putAll(zipped.map { (run, manifest, config) ->
            buildMap {
                for ((key, value) in run.env) {
                    val argument = if (value.startsWith('$')) {
                        MinecraftRunConfiguration.Argument(resolveTemplate(manifest, config, value.substring(2, value.length - 1), taskName))
                    } else if (value.startsWith('{')) {
                        MinecraftRunConfiguration.Argument(resolveTemplate(manifest, config, value.substring(1, value.length - 1), taskName))
                    } else {
                        MinecraftRunConfiguration.Argument(value)
                    }

                    put(key, argument)
                }
            }
        })

        arguments.addAll(zipped.map { (run, manifest, config) ->
            val arguments = mutableSetOf<MinecraftRunConfiguration.Argument>()

            addArgs(manifest, config, arguments, run.args, taskName)

            arguments
        })

        jvmArguments.addAll(zipped.map { (run, manifest, config) ->
            val jvmArguments = mutableSetOf<MinecraftRunConfiguration.Argument>()

            addArgs(manifest, config, jvmArguments, run.jvmArgs, taskName)

            for ((key, value) in run.props) {
                if (value.startsWith('{')) {
                    val template = value.substring(1, value.length - 1)
                    if (template == "minecraft_classpath_file") {
                        val configuration = project.configurations.getByName(sourceSet.get().runtimeClasspathConfigurationName).resolvedConfiguration.resolvedArtifacts

                        val minecraftJars = configuration.filter {
                            it.moduleVersion.id.group == MinecraftComponentResolvers.GROUP && (it.moduleVersion.id.name == MinecraftComponentResolvers.COMMON_MODULE || it.moduleVersion.id.name == MinecraftComponentResolvers.CLIENT_MODULE)
                        }.joinToString(",") { it.file.absolutePath }

                        val fmlJars = configuration.filter {
                            it.moduleVersion.id.group == "net.minecraftforge" && (it.moduleVersion.id.name == "fmlcore" || it.moduleVersion.id.name == "javafmllanguage" || it.moduleVersion.id.name == "mclanguage")
                        }.joinToString(",") { it.file.absolutePath }

                        jvmArguments.add(MinecraftRunConfiguration.Argument("-DminecraftCodev.minecraftJars=$minecraftJars"))
                        jvmArguments.add(MinecraftRunConfiguration.Argument("-DminecraftCodev.fmlJars=$fmlJars"))
                    } else {
                        jvmArguments.add(MinecraftRunConfiguration.Argument("-D$key=", resolveTemplate(manifest, config, template, taskName)))
                    }
                } else {
                    jvmArguments.add(MinecraftRunConfiguration.Argument("-D$key=$value"))
                }
            }

            jvmArguments
        })
    }

    fun client(): ForgeRunsDefaultsContainer = apply {
        defaults.builder.action {
            addData(::client.name, UserdevConfig.Runs::client, true)
        }
    }

    fun server(): ForgeRunsDefaultsContainer = apply {
        defaults.builder.action {
            addData(::server.name, UserdevConfig.Runs::server, false)
        }
    }

    fun data(): ForgeRunsDefaultsContainer = apply {
        defaults.builder.action {
            addData(::data.name, UserdevConfig.Runs::data, false)
        }
    }

    fun gameTestServer(): ForgeRunsDefaultsContainer = apply {
        defaults.builder.setup {
            project.plugins.withType(JvmEcosystemPlugin::class.java) {
                sourceSet.convention(project.extensions.getByType(SourceSetContainer::class.java).getByName(SourceSet.TEST_SOURCE_SET_NAME))
            }
        }

        defaults.builder.action {
            addData(::data.name, UserdevConfig.Runs::gameTestServer, false)
        }
    }

    companion object {
        private const val WRONG_SIDE_ERROR = "There is no Minecraft %s dependency for defaults.forge.%s to work"

        val RunConfigurationDefaultsContainer.forge
            get() = ForgeRunsDefaultsContainer(this)
    }
}
