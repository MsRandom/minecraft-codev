package net.msrandom.minecraftcodev.forge

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor

// Simply skips non-class elements
class ClassNameVisitor(next: MappingVisitor) : ForwardingMappingVisitor(next) {
    override fun visitMethod(srcName: String, srcDesc: String) = false
    override fun visitField(srcName: String, srcDesc: String) = false
}
