package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor

class FieldAddDescVisitor(next: MappingVisitor) : ForwardingMappingVisitor(next) {
    override fun visitField(
        srcName: String,
        srcDesc: String?,
    ) = super.visitField(srcName, srcDesc ?: "null")
}
