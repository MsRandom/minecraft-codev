package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.osVersion
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.internal.impldep.org.apache.commons.lang.SystemUtils
import org.gradle.internal.os.OperatingSystem

abstract class RunConfigurationDefaultsContainer(val project: Project, val builder: MinecraftRunConfigurationBuilder) : ExtensionAware {
    private val detachedConfiguration = project.configurations.detachedConfiguration()

    private fun ruleMatches(os: MinecraftVersionMetadata.Rule.OperatingSystem): Boolean {
        if (os.name != null && OperatingSystem.forName(os.name) != OperatingSystem.current()) return false
        if (os.version != null && osVersion() matches Regex(os.version!!)) return false
        if (os.arch != null && os.arch != SystemUtils.OS_ARCH) return false

        return true
    }

    fun client(): RunConfigurationDefaultsContainer = apply {
        builder.action {
/*            val extension = project.minecraftCodev
            val version = findMinecraftVersion(sourceSet, MinecraftRepositoryAccess.CLIENT)
                ?: throw UnsupportedOperationException(WRONG_SIDE_ERROR.format(MinecraftRepositoryAccess.CLIENT))

            val manifest = project.serviceOf<RepositoriesSupplier>().get().filterIsInstance<MinecraftRepositoryImpl>().firstNotNullOfOrNull {
                RawMinecraftRepositoryAccess.getVersionManifest(version, it.url, it.transport.resourceAccessor, project.serviceOf(), null)
            }!!

            mainClass.set(manifest.mainClass)

            jvmVersion.set(manifest.javaVersion.majorVersion)

            beforeRunTasks.add(MinecraftCodevPlugin.DOWNLOAD_ASSETS)

            val arguments = manifest.arguments.game.ifEmpty {
                manifest.minecraftArguments.split(' ').map {
                    MinecraftVersionMetadata.Argument(emptyList(), listOf(it))
                }
            }

            val jvmArguments = manifest.arguments.jvm

            val fixedArguments = mutableListOf<MinecraftRunConfiguration.Argument>()
            val fixedJvmArguments = mutableListOf<MinecraftRunConfiguration.Argument>()

            for (argument in arguments) {
                if (argument.rules.isEmpty()) {
                    for (value in argument.value) {
                        if (value.startsWith("\${") && value.endsWith("}")) {
                            when (value.subSequence(2, value.length - 1)) {
                                "version_name" -> fixedArguments.add(MinecraftRunConfiguration.Argument(version))
                                "assets_root" -> fixedArguments.add(MinecraftRunConfiguration.Argument(extension.assets))
                                "assets_index_name" -> fixedArguments.add(MinecraftRunConfiguration.Argument(manifest.assets))
                                "game_assets" -> fixedArguments.add(MinecraftRunConfiguration.Argument(extension.resources))
                                "auth_access_token" -> fixedArguments.add(MinecraftRunConfiguration.Argument(Random.nextLong()))
                                "user_properties" -> fixedArguments.add(MinecraftRunConfiguration.Argument("{}"))
                                else -> fixedArguments.removeLastOrNull()
                            }
                        } else {
                            fixedArguments.add(MinecraftRunConfiguration.Argument(value))
                        }
                    }
                }
            }

            ARGUMENTS@ for (argument in jvmArguments) {
                var matches = argument.rules.isEmpty()

                if (!matches) {
                    for (rule in argument.rules) {
                        if (rule.action == MinecraftVersionMetadata.RuleAction.Allow) {
                            if (rule.os == null) {
                                continue
                            }

                            if (!ruleMatches(rule.os)) {
                                continue@ARGUMENTS
                            }

                            matches = true
                        } else {
                            if (rule.os == null) {
                                continue
                            }

                            if (ruleMatches(rule.os)) {
                                continue@ARGUMENTS
                            }
                        }
                    }
                }

                if (matches) {
                    for (value in argument.value) {
                        if (value == "\${classpath}") {
                            fixedJvmArguments.removeLast()
                            continue
                        } else if (value.startsWith("-Dminecraft.launcher")) {
                            continue
                        } else {
                            val templateStart = value.indexOf("\${")
                            if (templateStart != -1) {
                                *//*val template = value.subSequence(templateStart + 2, value.indexOf('}'))
                                if (template == "natives_directory") {
                                    val nativesDirectory = project.tasks.withType(CopyNatives::class.java).getByName(MinecraftCodevPlugin.COPY_NATIVES).output.asFile.get().toPath()
                                    fixedJvmArguments.add(MinecraftRunConfiguration.Argument(value.substring(0, templateStart), nativesDirectory))
                                } else {
                                    continue
                                }*//*
                                continue
                            } else {
                                if (' ' in value) {
                                    fixedJvmArguments.add(MinecraftRunConfiguration.Argument("\"$value\""))
                                } else {
                                    fixedJvmArguments.add(MinecraftRunConfiguration.Argument(value))
                                }
                            }
                        }
                    }
                }
            }

            this.arguments.addAll(fixedArguments)
            this.jvmArguments.addAll(fixedJvmArguments)*/
        }
    }

    fun server(): RunConfigurationDefaultsContainer = apply {
        builder.action {
/*            val version = findMinecraftVersion(sourceSet, MinecraftRepositoryAccess.COMMON)
                ?: throw UnsupportedOperationException(WRONG_SIDE_ERROR.format(MinecraftRepositoryAccess.COMMON))

            val manifest = project.serviceOf<RepositoriesSupplier>().get().filterIsInstance<MinecraftRepositoryImpl>().firstNotNullOfOrNull {
                RawMinecraftRepositoryAccess.getVersionManifest(version, it.url, it.transport.resourceAccessor, project.serviceOf(), null)
            }!!

            mainClass.set(
                project.provider {
                    val serverJar = project.serviceOf<RepositoriesSupplier>().get().filterIsInstance<MinecraftRepositoryImpl>().firstNotNullOfOrNull {
                        // TODO don't use serviceOf
                        RawMinecraftRepositoryAccess.resolveMojangFile(
                            detachedConfiguration.name,
                            detachedConfiguration.resolutionStrategy as ResolutionStrategyInternal,
                            project.serviceOf(),
                            it.rawRepository,
                            project.serviceOf(),
                            project.serviceOf(),
                            project.serviceOf(),
                            RawMinecraftRepositoryAccess.GROUP,
                            MinecraftRepositoryAccess.SERVER,
                            version
                        )
                    } ?: throw UnsupportedOperationException("Version $version does not have a server.")

                    zipFileSystem(serverJar.toPath()).use {
                        val mainPath = it.getPath("META-INF/main-class")
                        if (mainPath.exists()) {
                            String(mainPath.readBytes())
                        } else {
                            it.getPath(JarFile.MANIFEST_NAME).inputStream().use(::Manifest).mainAttributes.getValue(Attributes.Name.MAIN_CLASS)
                        }
                    }
                }
            )

            jvmVersion.set(manifest.javaVersion.majorVersion)*/
        }
    }

    companion object {
        private const val WRONG_SIDE_ERROR = "There is no Minecraft %s dependency for defaults.%s to work"

/*        internal fun MinecraftRunConfiguration.findMinecraftVersion(sourceSet: Provider<SourceSet>, type: String): String? {
            return project.configurations.getByName(sourceSet.get().runtimeClasspathConfigurationName).resolvedConfiguration.resolvedArtifacts.firstOrNull {
                it.moduleVersion.id.group == MinecraftRepositoryAccess.GROUP && it.moduleVersion.id.name == type
            }?.moduleVersion?.id?.version
        }*/
    }
}
