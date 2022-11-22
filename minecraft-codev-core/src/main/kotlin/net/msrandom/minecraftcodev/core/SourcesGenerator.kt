package net.msrandom.minecraftcodev.core

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.decompiler.SingleFileSaver
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
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

                MinecraftCodevPlugin.zipFileSystem(Path(externalPath)).use {
                    it.getPath(internalPath).readBytes()
                }
            },
            SingleFileSaver(output.toFile()),
            emptyMap(),
            object : IFernflowerLogger() {
                override fun writeMessage(message: String, severity: Severity?) {}

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
