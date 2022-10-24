package net.msrandom.minecraftcodev.forge

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor
import net.fabricmc.mappingio.tree.MappingTreeView
import org.objectweb.asm.Type
import java.nio.file.Path
import kotlin.io.path.useLines

class ParameterNameVisitor(next: MappingVisitor, constructors: Path, private val namespace: String, private val tree: MappingTreeView) : ForwardingMappingVisitor(next) {
    private val constructors = constructors.useLines { lines ->
        lines.associate {
            val parts = it.split(' ')
            "${parts[1]} ${parts[2]}" to parts[0]
        }
    }

    private val treeNamespaceId = tree.getNamespaceId(namespace)
    private var namespaceId = MappingTreeView.NULL_NAMESPACE_ID
    private var currentClass: String? = null
    private var argumentCount = -1
    private var methodId: String? = null
    private val visited = hashSetOf<Int>()

    override fun visitNamespaces(srcNamespace: String, dstNamespaces: MutableList<String>) {
        namespaceId = if (namespace == srcNamespace) {
            MappingTreeView.SRC_NAMESPACE_ID
        } else {
            val id = dstNamespaces.indexOf(namespace)
            if (id == -1) {
                MappingTreeView.NULL_NAMESPACE_ID
            } else {
                id
            }
        }

        super.visitNamespaces(srcNamespace, dstNamespaces)
    }

    override fun visitClass(srcName: String): Boolean {
        finishLast()
        return super.visitClass(srcName)
    }

    override fun visitMethod(srcName: String, srcDesc: String): Boolean {
        finishLast()

        argumentCount = Type.getMethodType(srcDesc).argumentTypes.size
        return super.visitMethod(srcName, srcDesc)
    }

    override fun visitDstName(targetKind: MappedElementKind, namespace: Int, name: String) {
        super.visitDstName(targetKind, namespace, name)

        if (namespace == namespaceId) {
            if (targetKind == MappedElementKind.CLASS) {
                currentClass = name
            } else if (targetKind == MappedElementKind.METHOD) {
                methodId = if (name == "<init>") {
                    // FIXME this doesn't work because constructors aren't always included
                    val desc = tree.getClass(currentClass, treeNamespaceId)?.getMethod("<init>", this.namespace)?.getDesc(namespaceId)
                    if (desc == null) {
                        null
                    } else {
                        constructors["$currentClass $desc"]
                    }
                } else if (name.startsWith("func_")) {
                    name.substring(5, name.indexOf('_', 5))
                } else if (name == "<cinit>") {
                    null
                } else {
                    name
                }
            }
        }
    }

    override fun visitMethodArg(argPosition: Int, lvIndex: Int, srcName: String?): Boolean {
        if (methodId != null) {
            visited.add(lvIndex)
        }

        return super.visitMethodArg(argPosition, lvIndex, srcName)
    }

    override fun visitEnd(): Boolean {
        finishLast()
        return super.visitEnd()
    }

    private fun finishLast() {
        if (currentClass != null && argumentCount != -1 && methodId != null) {
            repeat(argumentCount) {
                if (it !in visited) {
                    if (super.visitMethodArg(-1, it, "o")) {
                        super.visitDstName(MappedElementKind.METHOD_ARG, namespaceId, "p_${methodId}_${it}_")
                    }
                }
            }

            argumentCount = -1
            methodId = null
            visited.clear()
        }
    }
}
