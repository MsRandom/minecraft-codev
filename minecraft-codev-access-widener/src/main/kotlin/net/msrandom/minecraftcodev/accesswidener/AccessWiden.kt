package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.core.resolve.isCodevGeneratedMinecraftJar
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTransform
abstract class AccessWiden : TransformAction<AccessWiden.Parameters> {
    abstract class Parameters : TransformParameters {
        abstract val accessWideners: ConfigurableFileCollection
            @InputFiles
            @PathSensitive(PathSensitivity.RELATIVE)
            get

        abstract val namespace: Property<String>
            @Input
            @Optional
            get
    }

    abstract val inputFile: Provider<FileSystemLocation>
        @InputArtifact
        @PathSensitive(PathSensitivity.NONE)
        get

    override fun transform(outputs: TransformOutputs) {
        val input = inputFile.get().toPath()

        if (parameters.accessWideners.isEmpty || !isCodevGeneratedMinecraftJar(input)) {
            outputs.file(inputFile)

            return
        }

        println("Access widening $input")

        val accessModifiers = loadAccessWideners(parameters.accessWideners, parameters.namespace.takeIf(Property<*>::isPresent)?.get())

        val output = outputs.file("${input.nameWithoutExtension}-access-widened.${input.extension}").toPath()

        zipFileSystem(input).use { inputZip ->
            zipFileSystem(output, true).use { outputZip ->
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
