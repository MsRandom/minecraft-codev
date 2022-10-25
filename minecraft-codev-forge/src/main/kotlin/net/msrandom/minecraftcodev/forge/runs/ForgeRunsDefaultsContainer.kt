package net.msrandom.minecraftcodev.forge.runs

import net.minecraftforge.srgutils.IMappingBuilder
import net.minecraftforge.srgutils.IMappingFile
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.minecraftCodev
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.runs.MinecraftRunConfiguration
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import org.gradle.api.Project
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

class ForgeRunsDefaultsContainer(private val defaults: RunConfigurationDefaultsContainer) {
    private fun getUserdevData(project: Project): UserdevConfig? {
        for (file in project.configurations.getByName(MinecraftCodevForgePlugin.PATCHES_CONFIGURATION)) {
            var config: UserdevConfig? = null
            val isUserdev = MinecraftCodevForgePlugin.userdevConfig(file) {
                config = it
            }

            if (isUserdev) return config
        }

        return null
    }

    private fun fail(): Nothing = throw UnsupportedOperationException("Forge userdev not in ${MinecraftCodevForgePlugin.PATCHES_CONFIGURATION}.")

    private fun MinecraftRunConfiguration.addArgs(manifest: MinecraftVersionMetadata, config: UserdevConfig, arguments: SetProperty<MinecraftRunConfiguration.Argument>, existing: List<String>) {
        arguments.addAll(
            existing.map {
                if (it.startsWith('{')) {
                    MinecraftRunConfiguration.Argument(resolveTemplate(manifest, config, it.substring(1, it.length - 1)))
                } else {
                    MinecraftRunConfiguration.Argument(it)
                }
            }
        )
    }

    private fun MinecraftRunConfiguration.resolveTemplate(manifest: MinecraftVersionMetadata, config: UserdevConfig, template: String): Any {
        return when (template) {
            "asset_index" -> manifest.assets
            "assets_root" -> project.minecraftCodev.assets
            "modules" -> config.modules.flatMapTo(mutableSetOf()) {
                project.configurations.detachedConfiguration(project.dependencies.create(it)).setTransitive(false)
            }.joinToString(File.pathSeparator)
            "MC_VERSION" -> manifest.id
            "mcp_mappings" -> "minecraftcodev.mappings"
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

/*                val mappings = project.minecraftCodev.remapper.mappings
                val tree = mappings.tree
                val sourceNamespace = tree.getNamespaceId(MappingNamespace.SRG)
                val targetNamespace = tree.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
                for (type in tree.classes) {
                    val addedClass = srgMappings.addClass(type.getName(targetNamespace), type.getName(sourceNamespace))

                    for (field in type.fields) {
                        addedClass.field(field.getName(sourceNamespace), field.getName(targetNamespace))
                    }

                    for (method in type.methods) {
                        addedClass.method(method.getDesc(sourceNamespace), method.getName(sourceNamespace), method.getName(targetNamespace))
                    }
                }*/

                // TODO fix this, somehow
                val path = project.layout.buildDirectory.dir("mcp-to-srg").get().file("mcp.srg").asFile.toPath()
                srgMappings.build().write(path, IMappingFile.Format.SRG)
                path
            }
            // TODO remove any argument containing natives
            "natives" -> ""
            else -> {
                project.logger.warn("Unknown Forge userdev run configuration template $template")
                template
            }
        }
    }

    private fun MinecraftRunConfiguration.addData(version: String, config: UserdevConfig, run: UserdevConfig.Run) {
/*        val manifest = project.serviceOf<RepositoriesSupplier>().get().filterIsInstance<MinecraftRepositoryImpl>().firstNotNullOfOrNull {
            RawMinecraftRepositoryAccess.getVersionManifest(version, it.url, it.transport.resourceAccessor, project.serviceOf(), null)
        }!!

        mainClass.set(run.main)

        addArgs(manifest, config, arguments, run.args)

        addArgs(manifest, config, jvmArguments, run.jvmArgs)

        for ((key, value) in run.env) {
            val argument = if (value.startsWith('$')) {
                MinecraftRunConfiguration.Argument(resolveTemplate(manifest, config, value.substring(2, value.length - 1)))
            } else if (value.startsWith('{')) {
                MinecraftRunConfiguration.Argument(resolveTemplate(manifest, config, value.substring(1, value.length - 1)))
            } else {
                MinecraftRunConfiguration.Argument(value)
            }

            environment.put(key, argument)
        }

        for ((key, value) in run.props) {
            if (value.startsWith('{')) {
                val template = value.substring(1, value.length - 1)
                if (template == "minecraft_classpath_file") {
                    val configuration = project.configurations.getByName(sourceSet.get().runtimeClasspathConfigurationName).resolvedConfiguration.resolvedArtifacts

                    val minecraftJars = configuration.filter {
                        it.moduleVersion.id.group == MinecraftRepositoryAccess.GROUP && (it.moduleVersion.id.name == MinecraftRepositoryAccess.COMMON || it.moduleVersion.id.name == MinecraftRepositoryAccess.CLIENT)
                    }.joinToString(",") { it.file.absolutePath }

                    val fmlJars = configuration.filter {
                        it.moduleVersion.id.group == "net.minecraftforge" && (it.moduleVersion.id.name == "fmlcore" || it.moduleVersion.id.name == "javafmllanguage" || it.moduleVersion.id.name == "mclanguage")
                    }.joinToString(",") { it.file.absolutePath }

                    jvmArguments.add(MinecraftRunConfiguration.Argument("-DminecraftCodev.minecraftJars=$minecraftJars"))
                    jvmArguments.add(MinecraftRunConfiguration.Argument("-DminecraftCodev.fmlJars=$fmlJars"))
                } else {
                    jvmArguments.add(MinecraftRunConfiguration.Argument("-D$key=", resolveTemplate(manifest, config, template)))
                }
            } else {
                jvmArguments.add(MinecraftRunConfiguration.Argument("-D$key=$value"))
            }
        }*/
    }

    fun client(version: String): ForgeRunsDefaultsContainer = apply {
        defaults.builder.action {
/*
            val version = findMinecraftVersion(sourceSet, MinecraftRepositoryAccess.CLIENT)
                ?: throw IllegalArgumentException(WRONG_SIDE_ERROR.format(MinecraftRepositoryAccess.CLIENT, ::client.name))
*/

            val config = getUserdevData(project) ?: fail()

            addData(version, config, config.runs.client)

            beforeRunTasks.add(MinecraftCodevPlugin.DOWNLOAD_ASSETS)
        }
    }

    fun server(version: String): ForgeRunsDefaultsContainer = apply {
        defaults.builder.action {
/*
            val version = findMinecraftVersion(sourceSet, MinecraftRepositoryAccess.COMMON)
                ?: throw IllegalArgumentException(WRONG_SIDE_ERROR.format(MinecraftRepositoryAccess.COMMON, ::server.name))
*/

            val config = getUserdevData(project) ?: fail()
            addData(version, config, config.runs.server)
        }
    }

    fun data(version: String): ForgeRunsDefaultsContainer = apply {
        defaults.builder.action {
/*
            val version = findMinecraftVersion(sourceSet, MinecraftRepositoryAccess.COMMON)
                ?: throw IllegalArgumentException(WRONG_SIDE_ERROR.format(MinecraftRepositoryAccess.COMMON, ::server.name))
*/

            val config = getUserdevData(project) ?: fail()
            addData(version, config, config.runs.server)

            beforeRunTasks.add(MinecraftCodevPlugin.DOWNLOAD_ASSETS)
        }
    }

    fun gameTestServer(version: String): ForgeRunsDefaultsContainer = apply {
        defaults.builder.action {
            sourceSet.convention(project.extensions.getByType(SourceSetContainer::class.java).getByName(SourceSet.TEST_SOURCE_SET_NAME))

/*
            val version = findMinecraftVersion(sourceSet, MinecraftRepositoryAccess.COMMON)
                ?: throw IllegalArgumentException(WRONG_SIDE_ERROR.format(MinecraftRepositoryAccess.COMMON, ::server.name))
*/

            val config = getUserdevData(project) ?: fail()
            addData(version, config, config.runs.server)
        }
    }

    companion object {
        private const val WRONG_SIDE_ERROR = "There is no Minecraft %s dependency for defaults.forge.%s to work"

        val RunConfigurationDefaultsContainer.forge
            get() = ForgeRunsDefaultsContainer(this)
    }
}
