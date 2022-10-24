/*
package net.msrandom.minecraftcodev.mappings

import net.msrandom.minecraftcodev.MinecraftCodevExtension.Companion.decorate
import net.msrandom.minecraftcodev.gradle.GradleMetadata
import org.apache.commons.lang.StringUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.internal.snapshot.impl.CoercingStringValueSnapshot
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*

@Suppress("UNCHECKED_CAST")
object MappedDependency {
    private var blockReentry = false

    private fun GradleMetadata.Attributes?.attributeValues() = this?.values ?: emptyMap()

    private fun createDependency(
        project: Project,
        attributesFactory: ImmutableAttributesFactory,
        instantiator: NamedObjectInstantiator,
        variantDependency: GradleMetadata.VariantData.DependencyData,
        configuration: Configuration
    ): ExternalModuleDependency {
        val configurationDependency = project.configurations
            .filter { configuration in it.hierarchy }
            .flatMap { it.allDependencies }
            .filterIsInstance<ExternalModuleDependency>()
            .firstOrNull { it.group == variantDependency.group && it.name == variantDependency.module }

        if (configurationDependency == null) {
            val dependency = project.dependencies.create("${variantDependency.group}:${variantDependency.module}") as ExternalModuleDependency

            variantDependency.version?.let { version ->
                dependency.version {
                    version.requires?.let(it::require)
                    version.requires?.let(it::prefer)
                    version.strictly?.let(it::strictly)
                    it.reject(*version.rejects.toTypedArray())
                }
            }

            for (exclude in variantDependency.excludes) {
                dependency.exclude(mapOf("group" to exclude.group, "module" to exclude.module))
            }

            dependency.because(variantDependency.reason)

            dependency.attributes {
                var attributes = ImmutableAttributes.EMPTY
                for ((attribute, value) in variantDependency.attributes.attributeValues()) {
                    attributes = when (value) {
                        is Boolean -> attributesFactory.concat(attributes, Attribute.of(attribute, Boolean::class.javaObjectType), value)
                        is Int -> attributesFactory.concat(attributes, Attribute.of(attribute, Int::class.javaObjectType), value)
                        else -> attributesFactory.concat(attributes, Attribute.of(attribute, String::class.java), CoercingStringValueSnapshot(value.toString(), instantiator))
                    }
                }

                for (attribute in attributes.attributes.keySet()) {
                    it.attribute(attribute as Attribute<Any>, attributes.attributes.getAttribute(attribute)!!)
                }
            }

            dependency.capabilities {
                it.requireCapabilities(
                    *variantDependency.requestedCapabilities.map { capability ->
                        "${capability.group}:${capability.name}:${capability.version}"
                    }.toTypedArray()
                )
            }

            if (variantDependency.endorseStrictVersions) {
                dependency.endorseStrictVersions()
            } else {
                dependency.doNotEndorseStrictVersions()
            }

            if (variantDependency.thirdPartyCompatibility != null) {
                dependency.artifact { artifact ->
                    artifact.name = variantDependency.thirdPartyCompatibility.artifactSelector.name
                    artifact.type = variantDependency.thirdPartyCompatibility.artifactSelector.type
                    variantDependency.thirdPartyCompatibility.artifactSelector.extension?.let { artifact.extension = it }
                    variantDependency.thirdPartyCompatibility.artifactSelector.classifier?.let { artifact.classifier = it }
                }
            }

            return dependency
        }

        return configurationDependency.copy()
    }

    private fun createDependencyConstraint(
        project: Project,
        attributesFactory: ImmutableAttributesFactory,
        instantiator: NamedObjectInstantiator,
        variantDependency: GradleMetadata.VariantData.DependencyConstraintData,
        configuration: Configuration
    ): DependencyConstraint {
        val configurationDependency = configuration.allDependencyConstraints.firstOrNull { it.group == variantDependency.group && it.name == variantDependency.module }

        if (configurationDependency == null) {
            val dependency = project.dependencies.constraints.create("${variantDependency.group}:${variantDependency.module}")

            variantDependency.version?.let { version ->
                dependency.version {
                    version.requires?.let(it::require)
                    version.requires?.let(it::prefer)
                    version.strictly?.let(it::strictly)
                    it.reject(*version.rejects.toTypedArray())
                }
            }

            dependency.because(variantDependency.reason)

            dependency.attributes {
                var attributes = ImmutableAttributes.EMPTY
                for ((attribute, value) in variantDependency.attributes.attributeValues()) {
                    attributes = when (value) {
                        is Boolean -> attributesFactory.concat(attributes, Attribute.of(attribute, Boolean::class.javaObjectType), value)
                        is Int -> attributesFactory.concat(attributes, Attribute.of(attribute, Int::class.javaObjectType), value)
                        else -> attributesFactory.concat(attributes, Attribute.of(attribute, String::class.java), CoercingStringValueSnapshot(value.toString(), instantiator))
                    }
                }

                for (attribute in attributes.attributes.keySet()) {
                    it.attribute(attribute as Attribute<Any>, attributes.attributes.getAttribute(attribute)!!)
                }
            }

            return dependency
        }

        return configurationDependency
    }

    fun mapDependency(
        project: Project,
        attributesFactory: ImmutableAttributesFactory,
        instantiator: NamedObjectInstantiator,
        configuration: Configuration,
        sourceNamespace: String?,
        targetNamespace: String,
        dependency: ExternalModuleDependency,
        extension: Remapper
    ) {
        if (sourceNamespace == targetNamespace) return

        if (blockReentry) {
            return
        }

        blockReentry = true
        val (metadata, files, moduleVersion) = extension.fetchData(dependency, configuration)
        blockReentry = false

        if (sourceNamespace == null) {
            if (metadata.variants.any { it.attributes.attributeValues()[Remapper.SOURCE_MAPPINGS_ATTRIBUTE.name] == targetNamespace }) {
                return
            }
        }

        val group = extension.mappedRepository.resolve(moduleVersion.group.toString().replace('.', File.separatorChar))
        val module = artifact(group, moduleVersion.name, moduleVersion.version, "module")
        val directory = module.parent
        val namespaces = directory.resolve("namespaces")
        val namespaceMarker = namespaces.resolve(targetNamespace)

        if (module.exists() && namespaceMarker.exists()) {
            return
        }

        namespaces.createDirectories()

        val newVariants = mutableListOf<GradleMetadata.VariantData>()
        val unmappedFiles = hashMapOf<File, String>()
        val libraryFiles = hashMapOf<File, UnmappedFile>()
        val sourceFiles = hashMapOf<File, UnmappedFile>()
        val javadocFiles = hashMapOf<File, UnmappedFile>()

        for (variant in metadata.variants) {
            if (variant.files.isNotEmpty()) {
                val newFiles = mutableListOf<GradleMetadata.VariantData.FileData>()

                val usage = variant.attributes.attributeValues()[Usage.USAGE_ATTRIBUTE.name]

                fun file(fileData: GradleMetadata.VariantData.FileData) =
                    files[fileData.name] ?: throw UnsupportedOperationException("File ${fileData.name} was invalid.")

                for (fileData in variant.files) {
                    unmappedFiles[file(fileData)] = fileData.url
                }

                fun addFiles(fileMap: MutableMap<File, UnmappedFile>) {
                    for (fileData in variant.files) {
                        val file = file(fileData)
                        val name = "$targetNamespace-${file.name}"

                        val source = sourceNamespace
                            ?: variant.attributes.attributeValues()[Remapper.SOURCE_MAPPINGS_ATTRIBUTE.name]?.toString()
                            ?: throw UnsupportedOperationException("$moduleVersion did not contain a ${Remapper.SOURCE_MAPPINGS_ATTRIBUTE} attribute and no source namespace was manually specified.")

                        if (source == targetNamespace) continue

                        fileMap[file] = UnmappedFile(source, directory.resolve(fileData.name).resolveSibling(name), variant)
                        newFiles.add(GradleMetadata.VariantData.FileData("$targetNamespace-${fileData.name}", fileData.url.replace(file.name, name)))
                    }
                }

                if (usage == Usage.JAVA_API || usage == Usage.JAVA_RUNTIME) {
                    val category = variant.attributes.attributeValues()[Category.CATEGORY_ATTRIBUTE.name]

                    if (category == Category.LIBRARY) {
                        addFiles(libraryFiles)
                    } else if (category == Category.DOCUMENTATION) {
                        val docsType = variant.attributes.attributeValues()[DocsType.DOCS_TYPE_ATTRIBUTE.name]
                        if (docsType == DocsType.JAVADOC) {
                            addFiles(javadocFiles)
                        } else if (docsType == DocsType.SOURCES) {
                            addFiles(sourceFiles)
                        }
                    }
                }

                if (newFiles.isNotEmpty()) {
                    newVariants.add(
                        GradleMetadata.VariantData(
                            "$targetNamespace${StringUtils.capitalize(variant.name)}",
                            GradleMetadata.Attributes(variant.attributes.attributeValues() + mapOf(Remapper.MAPPINGS_ATTRIBUTE.name to targetNamespace)),
                            variant.dependencies,
                            variant.dependencyConstraints,
                            newFiles,
                            variant.capabilities
                        )
                    )
                }
            }
        }

        if (newVariants.isNotEmpty()) {
            val newMetadata = GradleMetadata(
                metadata.formatVersion,
                metadata.component,
                metadata.variants + newVariants
            )

            GradleMetadata.addMetadata(newMetadata, module)

            namespaceMarker.createFile()

            for ((file, name) in unmappedFiles) {
                val newPath = directory.resolve(name)

                if (newPath.notExists()) {
                    newPath.parent.createDirectories()
                    file.toPath().copyTo(newPath)
                }
            }

            for ((file, target) in libraryFiles) {
                val (namespace, destination, variant) = target

                if (destination.notExists()) {
                    val classpath = project.configurations.detachedConfiguration(
                        *variant.dependencies
                            .map { variantDependency -> createDependency(project, attributesFactory, instantiator, variantDependency, configuration) }
                            .toTypedArray()
                    ).decorate(project)

                    classpath.dependencyConstraints.addAll(
                        variant.dependencyConstraints.map { variantDependency ->
                            createDependencyConstraint(
                                project,
                                attributesFactory,
                                instantiator,
                                variantDependency,
                                configuration
                            )
                        }
                    )

                    val dependencyResolver = classpath.copy().decorate(project)

                    classpath.dependencyConstraints.addAll(
                        configuration.allDependencyConstraints.map { constraint ->
                            val copy = project.dependencies.constraints.create("${constraint.group}:${constraint.name}")

                            copy.version {
                                val version = constraint.versionConstraint
                                if (version.requiredVersion.isNotEmpty()) it.require(version.requiredVersion)
                                if (version.preferredVersion.isNotEmpty()) it.prefer(version.preferredVersion)
                                if (version.strictVersion.isNotEmpty()) it.strictly(version.strictVersion)
                                it.reject(*version.rejectedVersions.toTypedArray())
                            }

                            copy.because(constraint.reason)

                            copy.attributes {
                                for (attribute in constraint.attributes.keySet()) {
                                    val value = if (attribute == Remapper.MAPPINGS_ATTRIBUTE) namespace else constraint.attributes.getAttribute(attribute) as Any
                                    it.attribute(attribute as Attribute<Any>, value)
                                }
                            }

                            copy
                        }
                    )

                    for (attribute in configuration.attributes.keySet()) {
                        val value = if (attribute == Remapper.MAPPINGS_ATTRIBUTE) namespace else configuration.attributes.getAttribute(attribute) as Any
                        classpath.attributes.attribute(attribute as Attribute<Any>, value)
                        dependencyResolver.attributes.attribute(attribute, configuration.attributes.getAttribute(attribute) as Any)
                    }

                    for (classpathDependency in classpath.dependencies) {
                        if (classpathDependency is ModuleDependency) {
                            if (classpathDependency.attributes.getAttribute(Remapper.MAPPINGS_ATTRIBUTE) != null) {
                                classpathDependency.attributes {
                                    it.attribute(Remapper.MAPPINGS_ATTRIBUTE, namespace)
                                }
                            }
                        }
                    }

                    // Force dependencies like remapper net.minecraft:common to be remapped, if constraints allow it
                    dependencyResolver.dependencyConstraints.addAll(configuration.allDependencyConstraints)
                    dependencyResolver.resolve()

                    val classpathFiles = classpath.map(File::toPath)

                    destination.parent.createDirectories()

                    project.logger.lifecycle(":Remapping ${file.name} from $namespace to $targetNamespace")

                    JarRemapper.remap(extension.mappings.tree, namespace, targetNamespace, file.toPath(), destination, classpathFiles)
                }
            }

            for ((file, target) in javadocFiles) {
                val (namespace, destination) = target

                destination.parent.createDirectories()
                // TODO figure out how to remap javadoc
                file.toPath().copyTo(destination)
            }

            for ((file, target) in sourceFiles) {
                val (namespace, destination) = target

                destination.parent.createDirectories()
                // TODO use mercury or replay mod's remapper
                file.toPath().copyTo(destination)
            }
        }
    }

    data class UnmappedFile(val namespace: String, val file: Path, val variant: GradleMetadata.VariantData)
}
*/
