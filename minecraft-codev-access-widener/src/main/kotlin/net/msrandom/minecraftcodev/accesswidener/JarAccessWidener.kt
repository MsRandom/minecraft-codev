package net.msrandom.minecraftcodev.accesswidener

import net.fabricmc.accesswidener.AccessWidener
import net.fabricmc.accesswidener.AccessWidenerClassVisitor
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

object JarAccessWidener {
    fun accessWiden(accessWidener: AccessWidener, input: Path): Path {
        val output = Files.createTempFile("access-widened", ".tmp.jar")

        output.deleteExisting()

        zipFileSystem(input).use { inputZip ->
            zipFileSystem(output, true).use { outputZip ->
                inputZip.getPath("/").walk {
                    for (path in filter(Path::isRegularFile)) {
                        val name = path.toString()
                        val outputPath = outputZip.getPath(name)
                        outputPath.parent?.createDirectories()

                        if (name.endsWith(".class")) {
                            val reader = path.inputStream().use(::ClassReader)
                            val writer = ClassWriter(0)
                            reader.accept(AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, writer, accessWidener), 0)

                            outputPath.writeBytes(writer.toByteArray())
                        } else {
                            path.copyTo(outputPath, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    }
                }
            }
        }

        return output
    }
}
