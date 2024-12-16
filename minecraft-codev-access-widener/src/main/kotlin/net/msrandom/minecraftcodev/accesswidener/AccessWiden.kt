package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.core.resolve.isCodevGeneratedMinecraftJar
import net.msrandom.minecraftcodev.core.utils.*
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTask
abstract class AccessWiden : DefaultTask() {
    abstract val inputFile: RegularFileProperty
        @InputFile
        @Classpath
        get

    abstract val accessWideners: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val namespace: Property<String>
        @Input
        @Optional
        get

    abstract val outputFile: RegularFileProperty
        @OutputFile get

    init {
        outputFile.convention(
            project.layout.file(
                inputFile.map {
                    temporaryDir.resolve("${it.asFile.nameWithoutExtension}-access-widened.${it.asFile.extension}")
                },
            ),
        )
    }

    @TaskAction
    fun accessWiden() {
        outputFile.getAsPath().deleteIfExists()

        val input = inputFile.get().toPath()

        val accessModifiers = loadAccessWideners(accessWideners, namespace.takeIf(Property<*>::isPresent)?.get())

        zipFileSystem(input).use { inputZip ->
            zipFileSystem(outputFile.getAsPath(), true).use { outputZip ->
                inputZip.getPath("/").walk {
                    for (path in filter(Path::isRegularFile)) {
                        val name = path.toString()
                        val outputPath = outputZip.getPath(name)
                        outputPath.parent?.createDirectories()

                        if (!name.endsWith(".class")) {
                            path.copyTo(outputPath, StandardCopyOption.COPY_ATTRIBUTES)
                            continue
                        }

                        val className = name.substring(1, name.length - ".class".length)

                        if (!accessModifiers.canModifyAccess(className)) {
                            path.copyTo(outputPath, StandardCopyOption.COPY_ATTRIBUTES)
                            continue
                        }

                        val reader = path.inputStream().use(::ClassReader)
                        val writer = ClassWriter(0)

                        reader.accept(AccessModifierClassVisitor(Opcodes.ASM9, writer, accessModifiers), 0)

                        outputPath.writeBytes(writer.toByteArray())
                    }
                }
            }
        }
    }
}
