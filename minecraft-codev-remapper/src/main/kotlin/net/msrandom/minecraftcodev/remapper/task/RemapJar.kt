package net.msrandom.minecraftcodev.remapper.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Jar

abstract class RemapJar : Jar() {
    abstract val input: RegularFileProperty
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val sourceNamespace: Property<String>
        @Input get

    abstract val targetNamespace: Property<String>
        @Input get

    abstract val mappings: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        get

    init {
        run {
            sourceNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

            from(project.provider {
                val mappings = project
                    .extensions
                    .getByType(MinecraftCodevExtension::class.java)
                    .extensions
                    .getByType(RemapperExtension::class.java)
                    .loadMappings(mappings)

                val remapped = JarRemapper.remap(mappings, sourceNamespace.get(), targetNamespace.get(), input.asFile.get().toPath(), classpath, true)

                project.fileTree(remapped.toFile())
            })
        }
    }
}
