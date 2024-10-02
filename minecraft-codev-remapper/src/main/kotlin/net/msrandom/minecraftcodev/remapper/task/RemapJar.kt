package net.msrandom.minecraftcodev.remapper.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.remapper.loadMappings
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin

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
        @CompileClasspath
        get

    init {
        run {
            group = LifecycleBasePlugin.BUILD_GROUP

            sourceNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
        }
    }

    @TaskAction
    fun remap() {
        val remapper =
            project
                .extension<MinecraftCodevExtension>()
                .extension<RemapperExtension>()

        val mappings = loadMappings(mappings)

        JarRemapper.remap(
            mappings,
            sourceNamespace.get(),
            targetNamespace.get(),
            input.asFile.get().toPath(),
            archiveFile.get().asFile.toPath(),
            classpath,
        )
    }
}
