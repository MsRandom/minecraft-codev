package net.msrandom.minecraftcodev.decompiler

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.decompiler.SingleFileSaver
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readBytes

object SourcesGenerator {
    fun decompile(input: Path, classpath: Collection<Path>): Path {
        val output = Files.createTempFile("sources", ".tmp.jar")

        val decompiler = BaseDecompiler(
            { externalPath, internalPath ->
                if (internalPath == null) {
                    Path(externalPath).readBytes()
                }

                zipFileSystem(Path(externalPath)).use {
                    it.base.getPath(internalPath).readBytes()
                }
            },
            SingleFileSaver(output.toFile()),
            mapOf(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING to 1.toString()),
            object : IFernflowerLogger() {
                override fun writeMessage(message: String, severity: Severity) {}
                override fun writeMessage(message: String, severity: Severity, t: Throwable?) {}
            }
        )

        for (library in classpath) {
            decompiler.addLibrary(library.toFile())
        }

        decompiler.addSource(input.toFile())

        decompiler.decompileContext()

        return output
    }
}
