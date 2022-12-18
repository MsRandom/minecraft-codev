package net.msrandom.minecraftcodev.remapper.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo

abstract class RemapJar : Jar() {
    abstract val input: RegularFileProperty
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val sourceNamespace: Property<String>
        @Input get

    abstract val targetNamespace: Property<String>
        @Input get

    abstract val mappings: Property<Configuration>
        @Input get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        get

    init {
        run {
            group = LifecycleBasePlugin.BUILD_GROUP

            sourceNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
        }
    }

    @TaskAction
    fun remap() {
        val remapper = project
            .extensions
            .getByType(MinecraftCodevExtension::class.java)
            .extensions
            .getByType(RemapperExtension::class.java)

        // TODO This will break in contexts where the mapping rules expect an object factory with a resolver chain provider
        val mappings = remapper.loadMappings(mappings.get(), objectFactory)

        val remapped = JarRemapper.remap(remapper, mappings.tree, sourceNamespace.get(), targetNamespace.get(), input.asFile.get().toPath(), classpath)

        remapped.copyTo(archiveFile.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
