package net.msrandom.minecraftcodev.core.resolve.intersection

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes
import kotlin.streams.asSequence

object JarIntersection {
    private const val VISIBILITY_MASK = 0x7

    // Choose lower visibility of the two
    private fun accessIntersection(a: Int, b: Int): Int {
        val visibilityA = a and VISIBILITY_MASK
        val visibilityB = b and VISIBILITY_MASK

        fun visibilityOrdinal(value: Int) = when (value) {
            Opcodes.ACC_PRIVATE -> 0
            Opcodes.ACC_PROTECTED -> 2
            Opcodes.ACC_PUBLIC -> 3
            else -> 1
        }

        val visibility = if (visibilityOrdinal(visibilityA) > visibilityOrdinal(visibilityB)) {
            visibilityB
        } else {
            visibilityA
        }

        return a and VISIBILITY_MASK.inv() or visibility
    }

    private fun classIntersection(a: Path, b: Path): ByteArray {
        val readerA = a.inputStream().use(::ClassReader)
        val readerB = b.inputStream().use(::ClassReader)

        val nodeA = ClassNode()
        val nodeB = ClassNode()
        val node = ClassNode()

        readerA.accept(nodeA, 0)
        readerB.accept(nodeB, 0)

        if (nodeA.superName == nodeB.superName) {
            node.superName = nodeA.superName
        }

        node.interfaces = nodeA.interfaces.intersect(nodeB.interfaces.toSet()).toList()

        node.innerClasses = nodeA.innerClasses.intersect(nodeB.innerClasses.toSet()).toList()

        node.fields = nodeA.fields.mapNotNull { field ->
            nodeB.fields.firstOrNull { it.name == field.name && it.desc != field.desc }?.let {
                FieldNode(accessIntersection(it.access, field.access), it.name, it.desc, it.signature, null)
            }
        }

        node.methods = nodeA.methods.mapNotNull { method ->
            nodeB.methods.firstOrNull { it.name == method.name && it.desc != method.desc }?.let {
                Opcodes.ACC_PRIVATE
                MethodNode(accessIntersection(it.access, method.access), it.name, it.desc, it.signature, it.exceptions.toTypedArray())
            }
        }

        val writer = ClassWriter(0)

        node.accept(writer)

        return writer.toByteArray()
    }

    private fun attributesIntersection(a: Attributes, b: Attributes): Attributes {
        val keys = a.keys.intersect(b.keys)

        val values = keys.mapNotNull {
            val valueA = a[it]
            val valueB = b[it]

            if (valueA == valueB) {
                it to valueA
            } else {
                null
            }
        }

        return Attributes(values.size).apply { putAll(values) }
    }

    private fun manifestIntersection(a: Path, b: Path): Manifest {
        val manifestA = a.inputStream().use(::Manifest)
        val manifestB = b.inputStream().use(::Manifest)

        val manifest = Manifest()

        val attributeIntersections = manifestA.entries.keys.intersect(manifestB.entries.keys).map {
            it to attributesIntersection(manifestA.getAttributes(it), manifestB.getAttributes(it))
        }

        manifest.mainAttributes.putAll(attributesIntersection(manifestA.mainAttributes, manifestB.mainAttributes))
        manifest.entries.putAll(attributeIntersections)

        return manifest
    }

    private fun fileIntersection(a: Path, b: Path, output: Path) {
        val name = a.toString()

        if (name.endsWith(".class")) {
            output.writeBytes(classIntersection(a, b))
        } else if (name.endsWith(".MF")) {
            output.outputStream().use(manifestIntersection(a, b)::write)
        }
    }

    fun intersection(a: Path, b: Path): Path {
        val output = Files.createTempFile("intersection", ".jar")

        zipFileSystem(a).use { fileSystemA ->
            zipFileSystem(b).use { fileSystemB ->
                zipFileSystem(output, create = true).use { outputFileSystem ->
                    val pathsA = Files.walk(fileSystemA.base.getPath("/")).asSequence().map(Path::toString)
                    val pathsB = Files.walk(fileSystemB.base.getPath("/")).asSequence().map(Path::toString)

                    for (path in pathsA.toSet().intersect(pathsB.toSet())) {
                        fileIntersection(
                            fileSystemA.base.getPath(path),
                            fileSystemB.base.getPath(path),
                            outputFileSystem.base.getPath(path),
                        )
                    }
                }
            }
        }

        return output
    }
}
