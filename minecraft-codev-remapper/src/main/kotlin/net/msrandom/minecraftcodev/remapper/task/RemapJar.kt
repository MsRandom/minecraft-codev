package net.msrandom.minecraftcodev.remapper.task

import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.loadMappings
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

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

    abstract val extraFiles: MapProperty<String, File>
        @Optional
        @Input
        get

    abstract val execOperations: ExecOperations
        @Inject get

    init {
        run {
            group = LifecycleBasePlugin.BUILD_GROUP

            sourceNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
        }
    }

    @TaskAction
    fun remap() {
        val mappings = loadMappings(mappings, execOperations, extraFiles.get())

        JarRemapper.remap(
            mappings,
            sourceNamespace.get(),
            targetNamespace.get(),
            input.getAsPath(),
            archiveFile.get().toPath(),
            classpath,
        )
    }
}
