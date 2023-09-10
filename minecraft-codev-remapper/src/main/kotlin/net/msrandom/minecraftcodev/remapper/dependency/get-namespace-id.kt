package net.msrandom.minecraftcodev.remapper.dependency

import net.fabricmc.mappingio.tree.MappingTreeView

fun String.getNamespaceId(sourceNamespace: String, destinationNamespace: List<String>) = if (this == sourceNamespace) {
    MappingTreeView.SRC_NAMESPACE_ID
} else {
    val id = destinationNamespace.indexOf(this)
    if (id == -1) {
        MappingTreeView.NULL_NAMESPACE_ID
    } else {
        id
    }
}
