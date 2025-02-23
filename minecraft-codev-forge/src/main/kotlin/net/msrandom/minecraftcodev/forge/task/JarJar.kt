package net.msrandom.minecraftcodev.forge.task

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.forge.isComponentFromDependency
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin
import kotlin.io.path.deleteIfExists

abstract class JarJar : Jar() {
    abstract val includesConfiguration: Property<Configuration>
        @Internal
        get

    abstract val input: RegularFileProperty
        @InputFile
        get

    abstract val metadata: RegularFileProperty
        @Internal get

    init {
        group = LifecycleBasePlugin.BUILD_GROUP

        metadata.set(temporaryDir.resolve("metadata.json"))

        from(metadata) {
            it.into("META-INF/jarjar")
        }

        from(includesConfiguration) {
            it.into("META-INF/jars")
        }

        from(project.zipTree(input))

        doFirst { generateMetadata() }
    }

    private fun generateMetadata() {
        val includes = includesConfiguration.get()

        if (includes.incoming.artifacts.artifacts.isEmpty()) {
            metadata.getAsPath().deleteIfExists()

            return
        }

        val dependencies = includes.incoming.resolutionResult.allComponents.map(ResolvedComponentResult::getId).associateWith { componentId ->
            val dependency = includes.allDependencies.firstOrNull { dependency ->
                isComponentFromDependency(componentId, dependency)
            }

            dependency
        }

        JsonObject(
            mapOf(
                "jars" to JsonArray(includes.incoming.artifacts.map {
                    val dependency = dependencies[it.id.componentIdentifier]
                    val module = it.id.componentIdentifier as? ModuleComponentIdentifier

                    val constraintVersion = if (dependency is ExternalDependency) {
                        val strict = dependency.versionConstraint.strictVersion.takeUnless(String::isEmpty)
                        val required = dependency.versionConstraint.requiredVersion.takeUnless(String::isEmpty)
                        val preferred = dependency.versionConstraint.preferredVersion.takeUnless(String::isEmpty)

                        strict ?: required ?: preferred
                    } else {
                        null
                    }

                    val versionRange = constraintVersion
                        ?: dependency?.version
                        ?: module?.version
                        ?: ""

                    JsonObject(
                        mapOf(
                            "identifier" to JsonObject(
                                mapOf(
                                    "group" to JsonPrimitive(module?.group ?: dependency?.group.orEmpty()),
                                    "artifact" to JsonPrimitive(module?.module ?: dependency?.name ?: it.file.nameWithoutExtension),
                                )
                            ),
                            "version" to JsonObject(
                                mapOf(
                                    "range" to JsonPrimitive(versionRange),
                                    "artifactVersion" to JsonPrimitive(module?.version ?: dependency?.version)
                                )
                            ),

                            "path" to JsonPrimitive("META-INF/jars/${it.file.name}"),
                        )
                    )
                })
            )
        )
    }
}
