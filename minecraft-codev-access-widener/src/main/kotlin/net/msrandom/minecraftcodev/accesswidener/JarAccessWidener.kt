package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTask
abstract class AccessWidenJar : DefaultTask() {
    abstract val input: RegularFileProperty
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val accessWideners: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        get

    abstract val namespace: Property<String>
        @Input
        @Optional
        get

    abstract val output: RegularFileProperty
        @OutputFile get

    init {
        output.convention(
            project.layout.file(
                project.provider {
                    temporaryDir.resolve("access-widened-output.jar")
                },
            ),
        )
    }

    @TaskAction
    private fun accessWiden() {
        val accessWidener =
            project
                .extension<MinecraftCodevExtension>()
                .extension<AccessWidenerExtension>()
                .loadAccessWideners(accessWideners, namespace.takeIf(Property<*>::isPresent)?.get())

        val output = output.asFile.get().toPath()

        output.deleteIfExists()

        zipFileSystem(input.asFile.get().toPath()).use { inputZip ->
            zipFileSystem(output, true).use { outputZip ->
                inputZip.base.getPath("/").walk {
                    for (path in filter(Path::isRegularFile)) {
                        val name = path.toString()
                        val outputPath = outputZip.base.getPath(name)
                        outputPath.parent?.createDirectories()

                        if (name.endsWith(".class")) {
                            val className = name.substring(1, name.length - ".class".length)

                            if (accessWidener.canModifyAccess(className)) {
                                val reader = path.inputStream().use(::ClassReader)
                                val writer = ClassWriter(0)

                                reader.accept(AccessModifierClassVisitor(Opcodes.ASM9, writer, accessWidener), 0)

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
}
