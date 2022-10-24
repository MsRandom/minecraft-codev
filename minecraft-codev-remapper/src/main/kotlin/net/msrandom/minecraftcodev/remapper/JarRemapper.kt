package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting

object JarRemapper {
    fun remap(mappings: MappingTreeView, sourceNamespace: String, targetNamespace: String, input: Path, classpath: Collection<Path>): Path {
        val output = Files.createTempFile("remapped", ".tmp.jar")
        val sourceNamespaceId = mappings.getNamespaceId(sourceNamespace)
        val targetNamespaceId = mappings.getNamespaceId(targetNamespace)

        val remapper = TinyRemapper
            .newRemapper()
            .ignoreFieldDesc(true)
            .renameInvalidLocals(true)
            .rebuildSourceFilenames(true)
            .extraRemapper(InnerClassRemapper(mappings, sourceNamespaceId, targetNamespaceId))
            .withMappings {
                for (classMapping in mappings.classes) {
                    val className = classMapping.getName(sourceNamespaceId)?.also { name ->
                        val targetName = classMapping.getName(targetNamespaceId)

                        if (targetName != null) {
                            it.acceptClass(name, targetName)
                        }
                    } ?: classMapping.srcName

                    for (field in classMapping.fields) {
                        val sourceName = field.getName(sourceNamespaceId)
                        val targetName = field.getName(targetNamespaceId)

                        if (sourceName != null && targetName != null) {
                            it.acceptField(IMappingProvider.Member(className, sourceName, field.getDesc(sourceNamespaceId)), targetName)
                        }
                    }

                    for (method in classMapping.methods) {
                        val sourceName = method.getName(sourceNamespaceId)

                        val member = if (sourceName == null) {
                            IMappingProvider.Member(className, method.srcName, method.getDesc(sourceNamespaceId))
                        } else {
                            IMappingProvider.Member(className, sourceName, method.getDesc(sourceNamespaceId)).also { member ->
                                val targetName = method.getName(targetNamespaceId)

                                if (targetName != null) {
                                    it.acceptMethod(member, targetName)
                                }
                            }
                        }

                        for (argument in method.args) {
                            it.acceptMethodArg(member, argument.lvIndex, argument.getName(targetNamespaceId))
                        }

                        for (variable in method.vars) {
                            val targetName = variable.getName(targetNamespaceId)

                            if (targetName != null) {
                                it.acceptMethodVar(member, variable.lvIndex, variable.startOpIdx, variable.lvIndex, targetName)
                            }
                        }
                    }
                }
            }.build()

        try {
            output.deleteExisting()

            OutputConsumerPath.Builder(output).build().use {
                it.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper)

                remapper.readClassPath(*classpath.toTypedArray())
                remapper.readInputs(input)

                remapper.apply(it)
            }
        } finally {
            remapper.finish()
        }

        return output
    }
}
