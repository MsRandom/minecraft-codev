package net.msrandom.minecraftcodev.forge.task

import net.msrandom.minecraftcodev.core.utils.getAsPath
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import kotlin.io.path.writeLines

@CacheableTask
abstract class GenerateLegacyClasspath : DefaultTask() {
    abstract val output: RegularFileProperty
        @Internal get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        get

    init {
        output.set(temporaryDir.resolve("legacyClasspath.txt"))
    }

    @TaskAction
    fun generate() {
        output.getAsPath().writeLines(classpath.map(File::getAbsolutePath))
    }
}
