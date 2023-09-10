package net.msrandom.minecraftcodev.forge.mappings

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.remapper.dependency.getNamespaceId

class ClassNameReplacer(next: MappingVisitor, private val tree: MappingTreeView, private val targetNamespace: String, private val officialSourceNamespace: String, private val officialTargetNamespace: String) : ForwardingMappingVisitor(next) {
    private var targetNamespaceId = MappingTreeView.NULL_NAMESPACE_ID

    private var currentClassName: String? = null

    override fun visitNamespaces(srcNamespace: String, dstNamespaces: List<String>) {
        targetNamespaceId = targetNamespace.getNamespaceId(srcNamespace, dstNamespaces)

        super.visitNamespaces(srcNamespace, dstNamespaces)
    }

    override fun visitClass(srcName: String?) = if (super.visitClass(srcName)) {
        currentClassName = srcName
        true
    } else {
        false
    }

    override fun visitDstName(targetKind: MappedElementKind, namespace: Int, name: String) {
        if (targetKind == MappedElementKind.CLASS && namespace == targetNamespaceId) {
            if (currentClassName != null) {
                val newName = tree.getClass(currentClassName, tree.getNamespaceId(officialTargetNamespace))?.getName(officialSourceNamespace)

                if (newName == null) {
                    super.visitDstName(targetKind, namespace, name)
                } else {
                    super.visitDstName(targetKind, namespace, newName)
                }
            } else {
                super.visitDstName(targetKind, namespace, name)
            }
        } else {
            super.visitDstName(targetKind, namespace, name)
        }
    }
}
