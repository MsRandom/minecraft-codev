package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.dependency.LaunchWrapperTransformer
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependency
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.utils.named
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParserFactory
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.OperatingSystemFamily

@Suppress("unused")
abstract class MinecraftCodevExtension(private val project: Project, private val attributesFactory: ImmutableAttributesFactory) : ExtensionAware {
    private val capabilityNotationParser = CapabilityNotationParserFactory(false).create()!!

    operator fun invoke(name: Any, version: String?): MinecraftDependency =
        MinecraftDependencyImpl(name.toString(), version.orEmpty(), null).apply {
            setAttributesFactory(attributesFactory)
            setCapabilityNotationParser(capabilityNotationParser)
        }

    operator fun invoke(name: Any) =
        invoke(name, null)

    operator fun invoke(notation: Map<String, Any>) =
        invoke(notation.getValue("name"), notation["version"]?.toString())

    fun call(name: Any, version: String?) = invoke(name, version)

    fun call(name: Any) = invoke(name)

    fun call(notation: Map<String, Any>) = invoke(notation)

    fun transformLaunchWrapper() {
        project.dependencies.attributesSchema { schema ->
            schema.attribute(MinecraftCodevPlugin.LAUNCH_WRAPPER_TRANSFORMED_ATTRIBUTE)
        }

        project.plugins.withType(JvmEcosystemPlugin::class.java) {
            project.dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
                it.attributes.attribute(MinecraftCodevPlugin.LAUNCH_WRAPPER_TRANSFORMED_ATTRIBUTE, false)
            }
        }

        project.configurations.all { configuration ->
            configuration.attributes {
                it.attribute(MinecraftCodevPlugin.LAUNCH_WRAPPER_TRANSFORMED_ATTRIBUTE, true)
                it.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, project.objects.named(OperatingSystem.current().familyName))
            }
        }

        @Suppress("UnstableApiUsage")
        project.dependencies.registerTransform(LaunchWrapperTransformer::class.java) {
            it.from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(MinecraftCodevPlugin.LAUNCH_WRAPPER_TRANSFORMED_ATTRIBUTE, false)
            it.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(MinecraftCodevPlugin.LAUNCH_WRAPPER_TRANSFORMED_ATTRIBUTE, true)
        }

        project.dependencies.components { components ->
            components.withModule("${MinecraftComponentResolvers.GROUP}:launchwrapper") { module ->
                module.allVariants { variant ->
                    variant.withDependencies { dependencies ->
                        dependencies.add("cpw.mods:grossjava9hacks:1.3.3")
                    }
                }
            }
        }
    }
}
