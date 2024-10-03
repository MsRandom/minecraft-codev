package net.msrandom.minecraftcodev.forge.mappings

import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.forge.accesswidener.findAccessTransformers
import net.msrandom.minecraftcodev.remapper.ExtraFileRemapper
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.MappingSet
import java.nio.file.FileSystem
import kotlin.io.path.deleteExisting

class AccessTransformerRemapper : ExtraFileRemapper {
    override fun invoke(
        mappings: MappingTreeView,
        fileSystem: FileSystem,
        sourceNamespace: String,
        targetNamespace: String,
    ) {
        val sourceNamespaceId = mappings.getNamespaceId(sourceNamespace)
        val targetNamespaceId = mappings.getNamespaceId(targetNamespace)

        for (path in fileSystem.findAccessTransformers()) {
            val mappingSet = MappingSet.create()

            for (treeClass in mappings.classes) {
                val className = treeClass.getName(sourceNamespaceId) ?: continue
                val mapping = mappingSet.getOrCreateClassMapping(className)

                treeClass.getName(targetNamespaceId)?.let {
                    mapping.deobfuscatedName = it
                }

                for (field in treeClass.fields) {
                    val name = field.getName(sourceNamespaceId) ?: continue
                    val descriptor = field.getDesc(sourceNamespaceId)

                    val fieldMapping =
                        if (descriptor == null) {
                            mapping.getOrCreateFieldMapping(name)
                        } else {
                            mapping.getOrCreateFieldMapping(name, descriptor)
                        }

                    fieldMapping.deobfuscatedName = field.getName(targetNamespaceId)
                }

                for (method in treeClass.methods) {
                    val name = method.getName(sourceNamespaceId) ?: continue
                    val methodMapping = mapping.getOrCreateMethodMapping(name, method.getDesc(sourceNamespaceId))

                    method.getName(targetNamespaceId)?.let {
                        methodMapping.deobfuscatedName = it
                    }

                    for (argument in method.args) {
                        methodMapping
                            .getOrCreateParameterMapping(
                                argument.argPosition,
                            ).deobfuscatedName = argument.getName(targetNamespaceId)
                    }
                }
            }

            val accessTransformer = AccessTransformFormats.FML.read(path)
            path.deleteExisting()

            AccessTransformFormats.FML.write(path, accessTransformer.remap(mappingSet))
        }
    }
}
