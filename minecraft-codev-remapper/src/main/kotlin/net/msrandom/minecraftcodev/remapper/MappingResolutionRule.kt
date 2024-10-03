package net.msrandom.minecraftcodev.remapper

import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.*
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import org.gradle.api.Action
import org.gradle.api.file.FileCollection
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ServiceLoader
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class MappingTreeProvider(
    val tree: MemoryMappingTree,
) {
    fun withTree(
        sourceNamespace: String,
        action: Action<MemoryMappingTree>,
    ) {
        if (tree.srcNamespace != sourceNamespace) {
            // Need to switch source to match
            val newTree = MemoryMappingTree()

            tree.accept(MappingSourceNsSwitch(newTree, sourceNamespace))

            action.execute(newTree)

            newTree.accept(MappingSourceNsSwitch(tree, tree.srcNamespace))
        } else {
            action.execute(tree)
        }
    }
}

class MappingResolutionData(
    visitor: MappingTreeProvider,
    messageDigest: MessageDigest,
    val execOperations: ExecOperations,
    val extraFiles: Map<String, File>,
) : ResolutionData<MappingTreeProvider>(visitor, messageDigest)

interface MappingResolutionRule : ResolutionRule<MappingResolutionData>

interface ZipMappingResolutionRule : ZipResolutionRule<MappingResolutionData>

class ZipMappingResolutionRuleHandler :
    ZipResolutionRuleHandler<MappingResolutionData, ZipMappingResolutionRule>(ZipMappingResolutionRule::class),
    MappingResolutionRule

class ProguardMappingResolutionRule : MappingResolutionRule {
    private fun sourceNamespaceVisitor(data: MappingResolutionData) =
        if (data.visitor.tree.srcNamespace == MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE) {
            data.visitor.tree
        } else {
            MappingSourceNsSwitch(data.visitor.tree, data.visitor.tree.srcNamespace ?: MappingsNamespace.OBF)
        }

    override fun load(
        path: Path,
        extension: String,
        data: MappingResolutionData,
    ): Boolean {
        if (extension != "txt" && extension != "map") {
            return false
        }

        data.decorate(path.inputStream()).reader().use {
            ProGuardReader.read(
                it,
                MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
                MappingsNamespace.OBF,
                sourceNamespaceVisitor(data),
            )
        }

        return true
    }
}

class ParchmentJsonMappingResolutionHandler : MappingResolutionRule {
    override fun load(
        path: Path,
        extension: String,
        data: MappingResolutionData,
    ): Boolean {
        if (extension != "json") {
            return false
        }

        handleParchment(data, path)

        return true
    }
}

class ParchmentZipMappingResolutionRule : ZipMappingResolutionRule {
    override fun load(
        path: Path,
        fileSystem: FileSystem,
        isJar: Boolean,
        data: MappingResolutionData,
    ): Boolean {
        val parchmentJson = fileSystem.getPath("parchment.json")

        if (parchmentJson.notExists()) {
            return false
        }

        handleParchment(data, parchmentJson)

        return true
    }
}

val mappingResolutionRules = ServiceLoader.load(MappingResolutionRule::class.java).toList()

fun loadMappings(
    files: FileCollection,
    execOperations: ExecOperations,
    extraFiles: Map<String, File>,
): MappingTreeView {
    val tree = MemoryMappingTree()
    val md = MessageDigest.getInstance("SHA1")

    val data = MappingResolutionData(MappingTreeProvider(tree), md, execOperations, extraFiles)

    for (file in files) {
        for (rule in mappingResolutionRules) {
            if (rule.load(file.toPath(), file.extension, data)) {
                break
            }
        }
    }

    return tree
}

private fun handleParchment(
    data: MappingResolutionData,
    path: Path,
) {
    data.visitor.withTree(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE) {
        val visitor = it
        val parchment =
            data.decorate(path.inputStream()).use {
                json.decodeFromStream<Parchment>(it)
            }

        do {
            if (visitor.visitHeader()) {
                visitor.visitNamespaces(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE, emptyList())
            }

            if (visitor.visitContent()) {
                parchment.classes?.forEach CLASS_LOOP@{ classElement ->
                    if (!visitor.visitClass(classElement.name) || !visitor.visitElementContent(MappedElementKind.CLASS)) {
                        return@CLASS_LOOP
                    }

                    fun visitComment(
                        element: Parchment.Element,
                        type: MappedElementKind,
                    ) {
                        element.javadoc?.let {
                            if (it.lines.isNotEmpty()) {
                                visitor.visitComment(type, it.lines.joinToString("\n"))
                            }
                        }
                    }

                    visitComment(classElement, MappedElementKind.CLASS)

                    classElement.fields?.forEach FIELD_LOOP@{ fieldElement ->
                        if (!visitor.visitField(
                                fieldElement.name,
                                fieldElement.descriptor,
                            ) ||
                            !visitor.visitElementContent(MappedElementKind.METHOD)
                        ) {
                            return@FIELD_LOOP
                        }

                        visitComment(fieldElement, MappedElementKind.FIELD)
                    }

                    classElement.methods?.forEach METHOD_LOOP@{ methodElement ->
                        if (!visitor.visitMethod(
                                methodElement.name,
                                methodElement.descriptor,
                            ) ||
                            !visitor.visitElementContent(MappedElementKind.METHOD)
                        ) {
                            return@METHOD_LOOP
                        }

                        visitComment(methodElement, MappedElementKind.METHOD)

                        methodElement.parameters?.forEach { parameterElement ->
                            visitor.visitMethodArg(parameterElement.index, parameterElement.index, parameterElement.name)

                            visitComment(parameterElement, MappedElementKind.METHOD_ARG)
                        }
                    }
                }
            }
        } while (!visitor.visitEnd())
    }
}