package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.tree.MappingTreeView
import org.objectweb.asm.commons.Remapper

class InnerClassRemapper(private val mappings: MappingTreeView, private val sourceNamespace: Int, private val targetNamespace: Int) : Remapper() {
    override fun map(internalName: String): String {
        val innerIndex = internalName.lastIndexOf('$')
        if (innerIndex == -1) {
            return super.map(internalName)
        }

        val innerName = internalName.substring(0, innerIndex)
        val mapped = mappings.getClass(innerName, sourceNamespace)?.getName(targetNamespace) ?: map(innerName)
        return "$mapped\$${internalName.substring(innerIndex + 1)}"
    }
}
