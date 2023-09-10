package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.remapper.dependency.getNamespaceId
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting

object JarRemapper {
    fun remap(
        remapperExtension: RemapperExtension,
        mappings: MappingTreeView,
        sourceNamespace: String,
        targetNamespace: String,
        input: Path,
        classpath: Iterable<File>
    ): Path {
        val output = Files.createTempFile("remapped", ".tmp.jar")

        val remapper = TinyRemapper
            .newRemapper()
            .ignoreFieldDesc(true)
            .renameInvalidLocals(true)
            .rebuildSourceFilenames(true)
            .extraRemapper(InnerClassRemapper(mappings, mappings.getNamespaceId(sourceNamespace), mappings.getNamespaceId(targetNamespace)))
            .withMappings {
                val rebuild = mappings.srcNamespace != sourceNamespace

                val tree = if (rebuild) {
                    val newTree = MemoryMappingTree()

                    mappings.accept(MappingSourceNsSwitch(newTree, sourceNamespace))

                    newTree
                } else {
                    mappings
                }

                tree.accept(object : MappingVisitor {
                    var sourceNamespaceId: Int = MappingTreeView.NULL_NAMESPACE_ID
                    var targetNamespaceId: Int = MappingTreeView.NULL_NAMESPACE_ID

                    lateinit var currentClass: String

                    lateinit var currentFieldName: String
                    lateinit var currentFieldDesc: String

                    lateinit var currentMethodName: String
                    lateinit var currentMethodDesc: String

                    var currentArgIndex: Int = -1

                    override fun visitNamespaces(srcNamespace: String, dstNamespaces: List<String>) {
                        sourceNamespaceId = sourceNamespace.getNamespaceId(srcNamespace, dstNamespaces)
                        targetNamespaceId = targetNamespace.getNamespaceId(srcNamespace, dstNamespaces)
                    }

                    override fun visitClass(srcName: String): Boolean {
                        currentClass = srcName

                        return true
                    }

                    override fun visitField(srcName: String, srcDesc: String): Boolean {
                        currentFieldName = srcName
                        currentFieldDesc = srcDesc

                        return true
                    }

                    override fun visitMethod(srcName: String, srcDesc: String): Boolean {
                        currentMethodName = srcName
                        currentMethodDesc = srcDesc

                        return true
                    }

                    override fun visitMethodArg(argPosition: Int, lvIndex: Int, srcName: String?): Boolean {
                        currentArgIndex = lvIndex

                        return true
                    }

                    override fun visitMethodVar(lvtRowIndex: Int, lvIndex: Int, startOpIdx: Int, srcName: String) = false

                    // TODO does not support the source namespace in the tree being the source wanted
                    override fun visitDstName(targetKind: MappedElementKind, namespace: Int, name: String) {
                        if (namespace != targetNamespaceId) return

                        when (targetKind) {
                            MappedElementKind.CLASS -> it.acceptClass(currentClass, name)
                            MappedElementKind.FIELD -> it.acceptField(IMappingProvider.Member(currentClass, currentFieldName, currentFieldDesc), name)
                            MappedElementKind.METHOD -> it.acceptMethod(IMappingProvider.Member(currentClass, currentMethodName, currentMethodDesc), name)
                            MappedElementKind.METHOD_ARG -> it.acceptMethodArg(IMappingProvider.Member(currentClass, currentMethodName, currentMethodDesc), currentArgIndex, name)
                            MappedElementKind.METHOD_VAR -> {}
                        }
                    }

                    override fun visitComment(targetKind: MappedElementKind, comment: String) {}
                })

                if (rebuild) {
                    tree.accept(MappingSourceNsSwitch(mappings as MappingVisitor, mappings.srcNamespace))
                }
            }.build()

        try {
            output.deleteExisting()

            synchronized(remapperExtension) {
                OutputConsumerPath.Builder(output).build().use {
                    it.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper)

                    remapper.readClassPath(*classpath.map(File::toPath).toTypedArray())

                    remapper.readInputs(input)
                    remapper.apply(it)
                }
            }
        } finally {
            remapper.finish()
        }

        zipFileSystem(output).use {
            remapperExtension.remapFiles(mappings, it.base.getPath("/"), mappings.getNamespaceId(sourceNamespace), mappings.getNamespaceId(targetNamespace), targetNamespace)
        }

        return output
    }
}
