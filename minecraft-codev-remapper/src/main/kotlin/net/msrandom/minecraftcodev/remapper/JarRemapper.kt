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
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

object JarRemapper {
    @Synchronized
    fun remap(
        mappings: MappingTreeView,
        sourceNamespace: String,
        targetNamespace: String,
        input: Path,
        output: Path,
        classpath: Iterable<File>,
    ) {
        val remapper =
            TinyRemapper
                .newRemapper()
                .ignoreFieldDesc(true)
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .extraRemapper(
                    InnerClassRemapper(mappings, mappings.getNamespaceId(sourceNamespace), mappings.getNamespaceId(targetNamespace)),
                )
                .extraRemapper(
                    object : Remapper() {
                        private val sourceNamespaceId = mappings.getNamespaceId(sourceNamespace)
                        private val targetNamespaceId = mappings.getNamespaceId(targetNamespace)

                        override fun mapMethodName(
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) = mappings
                            .getMethod(owner, name, descriptor, sourceNamespaceId)
                            ?.getName(targetNamespaceId)
                            ?: super.mapMethodName(owner, name, descriptor)

                        override fun mapFieldName(
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) = mappings
                            .getField(owner, name, descriptor, sourceNamespaceId)
                            ?.getName(targetNamespaceId)
                            ?: super.mapMethodName(owner, name, descriptor)
                    },
                )
                .withMappings {
                    val rebuild = mappings.srcNamespace != sourceNamespace

                    val tree =
                        if (rebuild) {
                            val newTree = MemoryMappingTree()

                            mappings.accept(MappingSourceNsSwitch(newTree, sourceNamespace))

                            newTree
                        } else {
                            mappings
                        }

                    tree.accept(
                        object : MappingVisitor {
                            var targetNamespaceId: Int = MappingTreeView.NULL_NAMESPACE_ID

                            lateinit var currentClass: String

                            lateinit var currentName: String
                            var currentDesc: String? = null

                            var currentLvtRowIndex: Int = -1
                            var currentStartOpIndex: Int = -1
                            var currentLvIndex: Int = -1

                            override fun visitNamespaces(
                                srcNamespace: String,
                                dstNamespaces: List<String>,
                            ) {
                                targetNamespaceId = targetNamespace.getNamespaceId(srcNamespace, dstNamespaces)
                            }

                            override fun visitClass(srcName: String): Boolean {
                                currentClass = srcName

                                return true
                            }

                            override fun visitField(
                                srcName: String,
                                srcDesc: String?,
                            ): Boolean {
                                currentName = srcName
                                currentDesc = srcDesc

                                return true
                            }

                            override fun visitMethod(
                                srcName: String,
                                srcDesc: String,
                            ): Boolean {
                                currentName = srcName
                                currentDesc = srcDesc

                                return true
                            }

                            override fun visitMethodArg(
                                argPosition: Int,
                                lvIndex: Int,
                                srcName: String?,
                            ): Boolean {
                                currentLvIndex = lvIndex

                                return true
                            }

                            override fun visitMethodVar(
                                lvtRowIndex: Int,
                                lvIndex: Int,
                                startOpIdx: Int,
                                srcName: String?,
                            ): Boolean {
                                currentLvIndex = lvIndex
                                currentStartOpIndex = startOpIdx
                                currentLvtRowIndex = lvtRowIndex
                                return true
                            }

                            override fun visitDstName(
                                targetKind: MappedElementKind,
                                namespace: Int,
                                name: String,
                            ) {
                                if (namespace != targetNamespaceId) return

                                if (targetKind == MappedElementKind.CLASS) {
                                    return it.acceptClass(currentClass, name)
                                }

                                val member = IMappingProvider.Member(currentClass, currentName, currentDesc)

                                when (targetKind) {
                                    MappedElementKind.FIELD -> it.acceptField(member, name)
                                    MappedElementKind.METHOD -> it.acceptMethod(member, name)
                                    MappedElementKind.METHOD_ARG -> it.acceptMethodArg(member, currentLvIndex, name)
                                    MappedElementKind.METHOD_VAR ->
                                        it.acceptMethodVar(
                                            member,
                                            currentLvIndex,
                                            currentStartOpIndex,
                                            currentLvtRowIndex,
                                            name,
                                        )

                                    else -> {}
                                }
                            }

                            override fun visitComment(
                                targetKind: MappedElementKind,
                                comment: String,
                            ) {
                            }
                        },
                    )

                    if (rebuild) {
                        tree.accept(MappingSourceNsSwitch(mappings as MappingVisitor, mappings.srcNamespace))
                    }
                }.build()

        try {
            OutputConsumerPath.Builder(output).build().use {
                it.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper)

                CompletableFuture.allOf(
                    remapper.readClassPathAsync(*classpath.map(File::toPath).toTypedArray()),
                    remapper.readInputsAsync(input),
                ).join()

                remapper.apply(it)
            }
        } finally {
            remapper.finish()
        }

        zipFileSystem(output).use { fs ->
            remapFiles(mappings, fs, sourceNamespace, targetNamespace)
        }
    }
}
