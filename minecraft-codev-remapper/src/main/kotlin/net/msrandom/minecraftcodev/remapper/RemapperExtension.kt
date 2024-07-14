package net.msrandom.minecraftcodev.remapper

import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.*
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class MappingTreeProvider(val tree: MemoryMappingTree) {
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
) : ResolutionData<MappingTreeProvider>(visitor, messageDigest)

fun interface ExtraFileRemapper {
    operator fun invoke(
        mappings: MappingTreeView,
        fileSystem: FileSystem,
        sourceNamespace: String,
        targetNamespace: String,
    )
}

fun interface ModDetectionRule {
    operator fun invoke(fileSystem: FileSystem): Boolean
}

fun sourceNamespaceVisitor(
    data: MappingResolutionData,
    sourceNamespace: String,
) = if (data.visitor.tree.srcNamespace == sourceNamespace) {
    data.visitor.tree
} else {
    MappingSourceNsSwitch(data.visitor.tree, data.visitor.tree.srcNamespace ?: MappingsNamespace.OBF)
}

open class RemapperExtension
@Inject
constructor(objectFactory: ObjectFactory, private val project: Project) {
    val zipMappingsResolution = objectFactory.zipResolutionRules<MappingResolutionData>()
    val mappingsResolution = objectFactory.resolutionRules(zipMappingsResolution)
    val extraFileRemappers: ListProperty<ExtraFileRemapper> = objectFactory.listProperty(ExtraFileRemapper::class.java)
    val modDetectionRules: ListProperty<ModDetectionRule> = objectFactory.listProperty(ModDetectionRule::class.java)

    init {
        mappingsResolution.add { path, extension, data ->
            if (extension == "txt" || extension == "map") {
                data.decorate(path.inputStream()).reader().use {
                    ProGuardReader.read(
                        it,
                        MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
                        MappingsNamespace.OBF,
                        sourceNamespaceVisitor(data, MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE),
                    )
                }

                true
            } else {
                false
            }
        }

        mappingsResolution.add { path, extension, data ->
            if (extension != "json") {
                return@add false
            }

            handleParchment(data, path)

            true
        }

        zipMappingsResolution.add { _, fileSystem, _, data ->
            val parchmentJson = fileSystem.getPath("parchment.json")

            if (parchmentJson.notExists()) {
                return@add false
            }

            handleParchment(data, parchmentJson)

            true
        }

        modDetectionRules.add {
            it.getPath("pack.mcmeta").exists()
        }
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
                                ) || !visitor.visitElementContent(MappedElementKind.METHOD)
                            ) {
                                return@FIELD_LOOP
                            }

                            visitComment(fieldElement, MappedElementKind.FIELD)
                        }

                        classElement.methods?.forEach METHOD_LOOP@{ methodElement ->
                            if (!visitor.visitMethod(
                                    methodElement.name,
                                    methodElement.descriptor,
                                ) || !visitor.visitElementContent(MappedElementKind.METHOD)
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

    fun loadMappings(files: FileCollection): MappingTreeView {
        val tree = MemoryMappingTree()
        val md = MessageDigest.getInstance("SHA1")

        val data = MappingResolutionData(MappingTreeProvider(tree), md)

        for (file in files) {
            for (rule in mappingsResolution.get()) {
                if (rule.load(file.toPath(), file.extension, data)) {
                    break
                }
            }
        }

        return tree
    }

    fun remapFiles(
        mappings: MappingTreeView,
        fileSystem: FileSystem,
        sourceNamespace: String,
        targetNamespace: String,
    ) {
        for (extraMapper in extraFileRemappers.get()) {
            extraMapper(mappings, fileSystem, sourceNamespace, targetNamespace)
        }
    }

    fun isMod(path: Path): Boolean {
        val extension = path.extension

        if (extension != "zip" && extension != "jar") {
            return false
        }

        return zipFileSystem(path).use { (fs) ->
            modDetectionRules.get().any { it(fs) }
        }
    }
}
