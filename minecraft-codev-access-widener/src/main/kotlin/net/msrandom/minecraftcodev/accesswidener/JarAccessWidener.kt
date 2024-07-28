package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTask
abstract class AccessWiden : DefaultTask() {
    abstract val inputFiles: ConfigurableFileCollection
        @InputFiles
        @Classpath
        @SkipWhenEmpty
        get

    abstract val accessWideners: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val namespace: Property<String>
        @Input
        @Optional
        get

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    val outputFiles: FileCollection
        @OutputFiles get() = project.fileTree(outputDirectory)

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))
    }

    private fun accessWiden(
        accessModifiers: AccessModifiers,
        input: Path,
        output: Path,
    ) {
        zipFileSystem(input).use { inputZip ->
            zipFileSystem(output, true).use { outputZip ->
                inputZip.base.getPath("/").walk {
                    for (path in filter(Path::isRegularFile)) {
                        val name = path.toString()
                        val outputPath = outputZip.base.getPath(name)
                        outputPath.parent?.createDirectories()

                        if (name.endsWith(".class")) {
                            val className = name.substring(1, name.length - ".class".length)

                            if (accessModifiers.canModifyAccess(className)) {
                                val reader = path.inputStream().use(::ClassReader)
                                val writer = ClassWriter(0)

                                reader.accept(AccessModifierClassVisitor(Opcodes.ASM9, writer, accessModifiers), 0)

                                outputPath.writeBytes(writer.toByteArray())
                            } else {
                                path.copyTo(outputPath, StandardCopyOption.COPY_ATTRIBUTES)
                            }
                        } else {
                            path.copyTo(outputPath, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    }
                }
            }
        }
    }

    @TaskAction
    private fun accessWiden(inputChanges: InputChanges) {
        val accessWidener =
            project
                .extension<MinecraftCodevExtension>()
                .extension<AccessWidenerExtension>()
                .loadAccessWideners(accessWideners, namespace.takeIf(Property<*>::isPresent)?.get())

        for (inputChange in inputChanges.getFileChanges(inputFiles)) {
            val input = inputChange.file.toPath()
            val output = outputDirectory.asFile.get().toPath().resolve("${input.nameWithoutExtension}-access-widened.${input.extension}")

            if (inputChange.changeType == ChangeType.MODIFIED) {
                output.deleteExisting()
            } else if (inputChange.changeType == ChangeType.REMOVED) {
                output.deleteExisting()

                continue
            }

            accessWiden(accessWidener, input, output)
        }
    }
}
