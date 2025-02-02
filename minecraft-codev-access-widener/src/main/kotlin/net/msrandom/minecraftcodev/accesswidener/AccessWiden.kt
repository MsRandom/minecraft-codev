package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.getLocalCacheDirectoryProvider
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.core.utils.tryLink
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.*

const val ACCESS_WIDEN_OPERATION_VERSION = 1

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

    abstract val cacheDirectory: DirectoryProperty
        @Internal get

    abstract val objectFactory: ObjectFactory
        @Inject get

    init {
        outputFile.convention(
            project.layout.file(
                inputFile.map {
                    temporaryDir.resolve("${it.asFile.nameWithoutExtension}-access-widened.${it.asFile.extension}")
                },
            ),
        )

        cacheDirectory.set(getLocalCacheDirectoryProvider(project))
    }

    private fun accessWiden(outputPath: Path) {
        val input = inputFile.get().toPath()

        if (accessWideners.isEmpty) {
            outputPath.tryLink(input)

            return
        }

        val accessModifiers = loadAccessWideners(accessWideners, namespace.takeIf(Property<*>::isPresent)?.get())

        zipFileSystem(input).use { inputZip ->
            zipFileSystem(outputPath, true).use { outputZip ->
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

    @TaskAction
    fun accessWiden() {
        val cacheKey = objectFactory.fileCollection().apply {
            from(accessWideners)
            from(inputFile)
        }

        cacheExpensiveOperation(cacheDirectory.getAsPath(), "access-widen-$ACCESS_WIDEN_OPERATION_VERSION", cacheKey, outputFile.getAsPath()) { (output) ->
            accessWiden(output)
        }
    }
}
