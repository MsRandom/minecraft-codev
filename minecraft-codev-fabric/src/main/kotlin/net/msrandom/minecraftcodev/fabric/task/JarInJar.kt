package net.msrandom.minecraftcodev.fabric.task

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.tasks.Jar

abstract class JarInJar : Jar() {
    abstract val includes: RegularFileProperty
        @PathSensitive(PathSensitivity.ABSOLUTE)
        @InputFiles
        get

    abstract val input: RegularFileProperty
        @PathSensitive(PathSensitivity.RELATIVE)
        @InputFile
        get

    init {
        from(project.zipTree(input))

        from(includes) {
            it.into("META-INF/jars")
        }
    }
}
