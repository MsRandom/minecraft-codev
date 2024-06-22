package net.msrandom.minecraftcodev.forge.task

import net.msrandom.minecraftcodev.core.task.AddClientSideAnnotations
import net.msrandom.minecraftcodev.core.task.IsClientOnlyAnnotation
import org.objectweb.asm.*

class ForgeAnnotationFinderVisitor(visitor: AnnotationVisitor?) : AnnotationVisitor(Opcodes.ASM9, visitor), IsClientOnlyAnnotation {
    override var isClientOnlyAnnotation = false

    override fun visitEnum(
        name: String,
        descriptor: String,
        value: String,
    ) {
        if (name == "value" && descriptor == "Lnet/minecraftforge/api/distmarker/Dist;" && value == "CLIENT") {
            isClientOnlyAnnotation = true
        }

        super.visitEnum(name, descriptor, value)
    }
}

abstract class AddForgeClientSideAnnotations : AddClientSideAnnotations<ForgeAnnotationFinderVisitor>() {
    private fun interfaceClientAnnotation(
        annotationVisitor: AnnotationVisitor?,
        type: String,
    ) {
        if (annotationVisitor == null) return

        annotationVisitor.visitEnum("value", "Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT")
        annotationVisitor.visit("_interface", Type.getObjectType(type))
        annotationVisitor.visitEnd()
    }

    override fun addClientOnlyAnnotation(
        visitor: ClassVisitor,
        interfaces: List<String>,
    ) {
        if (interfaces.size == 1) {
            interfaceClientAnnotation(visitor.visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIn;", true), interfaces.first())

            return
        }

        val annotationVisitor = visitor.visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIns;", true) ?: return

        annotationVisitor.visitArray("value")

        for (type in interfaces) {
            interfaceClientAnnotation(annotationVisitor.visitAnnotation("value", "Lnet/minecraftforge/api/distmarker/OnlyIn;"), type)
        }

        annotationVisitor.visitEnd()
    }

    override fun addClientOnlyAnnotation(visitor: MethodVisitor) {
        val annotationVisitor = visitor.visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIn;", true) ?: return

        annotationVisitor.visitEnum("value", "Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT")
        annotationVisitor.visitEnd()
    }

    override fun getClientOnlyAnnotationVisitor(
        descriptor: String,
        visitor: AnnotationVisitor?,
    ): ForgeAnnotationFinderVisitor? {
        if (descriptor == "Lnet/minecraftforge/api/distmarker/OnlyIn;") {
            return ForgeAnnotationFinderVisitor(visitor)
        }

        return null
    }
}
