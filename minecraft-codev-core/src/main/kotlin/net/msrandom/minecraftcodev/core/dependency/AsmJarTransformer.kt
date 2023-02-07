package net.msrandom.minecraftcodev.core.dependency

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CompileClasspath
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes

abstract class AsmJarTransformer(private val className: String) : TransformAction<TransformParameters.None> {
    abstract val input: Provider<FileSystemLocation>
        @InputArtifact get

    abstract val dependencies: FileCollection
        @CompileClasspath
        @InputArtifactDependencies
        get

    override fun transform(outputs: TransformOutputs) {
        val replace = zipFileSystem(input.get().asFile.toPath()).use {
            val clientUserdev = it.getPath(className)
            if (clientUserdev.exists()) {
                println(":Transforming ${input.get().asFile.name}")

                true
            } else {
                false
            }
        }

        if (replace) {
            val output = outputs.file("transformed-${input.get().asFile.name}").toPath()
            input.get().asFile.toPath().copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)

            zipFileSystem(output).use {
                val path = it.getPath(className)
                val reader = path.inputStream().use(::ClassReader)
                val node = ClassNode()

                reader.accept(node, 0)

                editNode(node)

                val writer = object : ClassWriter(reader, COMPUTE_MAXS or COMPUTE_FRAMES) {
                    val urlClassLoader by lazy {
                        val files = dependencies.files
                        val urls = arrayOfNulls<URL>(files.size + 1)

                        urls[0] = output.toUri().toURL()
                        for ((index, file) in files.withIndex()) {
                            urls[index + 1] = file.toURI().toURL()
                        }

                        URLClassLoader(urls)
                    }

                    override fun getClassLoader() = urlClassLoader
                }

                node.accept(writer)

                path.writeBytes(writer.toByteArray())
            }
        } else {
            outputs.file(input)
        }
    }

    abstract fun editNode(node: ClassNode)
}
