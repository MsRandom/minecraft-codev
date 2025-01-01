package net.msrandom.minecraftcodev.remapper.task

import net.fabricmc.mappingio.format.Tiny2Reader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.getGlobalCacheDirectoryProvider
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
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

    abstract val mappings: RegularFileProperty
        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles
        @CompileClasspath
        get

    abstract val cacheDirectory: DirectoryProperty
        @Internal get

    init {
        run {
            group = LifecycleBasePlugin.BUILD_GROUP

            sourceNamespace.convention(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

            cacheDirectory.set(getGlobalCacheDirectoryProvider(project))
        }
    }

    @TaskAction
    fun remap() {
        val mappings = MemoryMappingTree()

        Tiny2Reader.read(this.mappings.asFile.get().reader(), mappings)

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
