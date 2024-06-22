package net.msrandom.minecraftcodev.accesswidener

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor

class AccessModifierClassVisitor(
    api: Int,
    classVisitor: ClassVisitor,
    private val modifiers: AccessModifiers,
) : ClassVisitor(api, classVisitor) {
    private lateinit var className: String
    private var classAccess = 0

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<String?>,
    ) {
        className = name
        classAccess = access

        super.visit(
            version,
            modifiers.getClassAccess(access, name),
            name,
            signature,
            superName,
            interfaces,
        )
    }

    override fun visitInnerClass(
        name: String,
        outerName: String?,
        innerName: String?,
        access: Int,
    ) = super.visitInnerClass(
        name,
        outerName,
        innerName,
        modifiers.getClassAccess(access, name),
    )

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor =
        super.visitField(
            modifiers.getFieldAccess(access, className, name, descriptor),
            name,
            descriptor,
            signature,
            value,
        )

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
    ): MethodVisitor =
        super.visitMethod(
            modifiers.getMethodAccess(access, className, name, descriptor),
            name,
            descriptor,
            signature,
            exceptions,
        )
}
