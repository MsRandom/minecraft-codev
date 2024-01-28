package net.msrandom.minecraftcodev.accesswidener

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerVisitor
import org.cadixdev.at.AccessChange
import org.cadixdev.at.ModifierChange
import org.objectweb.asm.Opcodes

private const val VISIBILITY_MASK = Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED

interface Intersectable<T : Intersectable<T>> {
    fun intersection(other: T): T
}

private fun <T : Intersectable<T>> findIntersection(a: T?, b: T?) = when {
    a == null -> b!!
    b == null -> a
    else -> a.intersection(b)
}

private fun AccessChange.intersection(other: AccessChange) =
    listOf(this, other).minByOrNull { it.ordinal } ?: AccessChange.NONE

private fun ModifierChange.intersection(other: ModifierChange) =
    if (this == other) other else ModifierChange.NONE

@Serializable
data class AccessModifiers(
    @Transient
    private val onlyTransitives: Boolean = false,

    var namespace: String? = null,
    val classes: MutableMap<String, ClassModel> = hashMapOf()
) : AccessWidenerVisitor {
    fun onlyTransitives() = copy(onlyTransitives = true)

    fun intersection(other: AccessModifiers): AccessModifiers {
        val classNames = classes.keys.intersect(other.classes.keys)

        val classes = classNames.associateWithTo(hashMapOf()) {
            findIntersection(classes[it], other.classes[it])
        }

        return AccessModifiers(
            onlyTransitives || other.onlyTransitives,
            namespace ?: other.namespace,
            classes
        )
    }

    fun visit(modifiers: AccessModifiers) {
        visitHeader(modifiers.namespace)

        for ((className, classModel) in modifiers.classes) {
            visitClass(className, classModel.accessChange)

            for ((methodName, methodModels) in classModel.methods) {
                for (methodModel in methodModels) {
                    visitMethod(className, methodName, methodModel.descriptor, methodModel.accessChange, methodModel.extendabilityChange)
                }
            }

            for ((fieldName, fieldModel) in classModel.fields) {
                visitField(className, fieldName, fieldModel.descriptor, fieldModel.accessChange, fieldModel.mutabilityChange)
            }
        }
    }

    private fun shouldVisit(transitive: Boolean) = transitive || !onlyTransitives

    override fun visitHeader(namespace: String?) {
        if (this.namespace != null && this.namespace != namespace) {
            throw UnsupportedOperationException("Namespace mismatch, expected ${this.namespace} got $namespace")
        }

        this.namespace = namespace
    }

    override fun visitField(owner: String, name: String, descriptor: String?, access: AccessWidenerReader.AccessType, transitive: Boolean) {
        if (!shouldVisit(transitive)) return

        val accessChange = if (access == AccessWidenerReader.AccessType.ACCESSIBLE) {
            AccessChange.PUBLIC
        } else {
            AccessChange.NONE
        }

        val extendabilityChange = if (access == AccessWidenerReader.AccessType.MUTABLE) {
            ModifierChange.REMOVE
        } else {
            ModifierChange.NONE
        }

        if (accessChange != AccessChange.NONE || extendabilityChange != ModifierChange.NONE) {
            visitField(owner, name, descriptor, accessChange, extendabilityChange)
        }
    }

    override fun visitMethod(owner: String, name: String, descriptor: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
        if (!shouldVisit(transitive)) return

        val accessChange = when (access) {
            AccessWidenerReader.AccessType.ACCESSIBLE -> AccessChange.PUBLIC
            AccessWidenerReader.AccessType.EXTENDABLE -> AccessChange.PROTECTED
            else -> AccessChange.NONE
        }

        val extendabilityChange = if (access == AccessWidenerReader.AccessType.EXTENDABLE) {
            ModifierChange.REMOVE
        } else {
            ModifierChange.NONE
        }

        if (accessChange != AccessChange.NONE) {
            visitMethod(owner, name, descriptor, accessChange, extendabilityChange)
        }
    }

    override fun visitClass(name: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
        if (!shouldVisit(transitive)) return

        val accessChange = when (access) {
            AccessWidenerReader.AccessType.ACCESSIBLE -> AccessChange.PUBLIC
            AccessWidenerReader.AccessType.EXTENDABLE -> AccessChange.PROTECTED
            else -> AccessChange.NONE
        }

        if (accessChange != AccessChange.NONE) {
            visitClass(name, accessChange)
        }
    }

    fun visitClass(name: String, accessChange: AccessChange) {
        classes.compute(name) { _, existing ->
            if (existing == null) {
                ClassModel(accessChange)
            } else {
                ClassModel(
                    accessChange.merge(existing.accessChange),
                    existing.methods,
                    existing.fields
                )
            }
        }
    }

    fun visitMethod(owner: String, name: String, descriptor: String, accessChange: AccessChange, extendabilityChange: ModifierChange) {
        val classModel = classes.computeIfAbsent(owner) { ClassModel() }

        var existingMethod = classModel.method(name, descriptor)

        if (existingMethod == null) {
            existingMethod = ClassModel.MethodModel(descriptor, accessChange, extendabilityChange)

            classModel.methods.computeIfAbsent(name) { mutableListOf() }.add(existingMethod)
        } else {
            val group = classModel.methods.getValue(name)

            group.remove(existingMethod)

            group.add(
                ClassModel.MethodModel(
                    descriptor,
                    accessChange.merge(existingMethod.accessChange),
                    extendabilityChange.merge(existingMethod.extendabilityChange)
                )
            )
        }
    }

    fun visitField(owner: String, name: String, descriptor: String?, accessChange: AccessChange, mutabilityChange: ModifierChange) {
        val classModel = classes.computeIfAbsent(owner) { ClassModel() }

        classModel.fields.compute(name) { _, existing ->
            if (existing?.descriptor != null && descriptor != null && existing.descriptor != descriptor) {
                throw UnsupportedOperationException("Field $owner.$name has two defined descriptors, previously ${existing.descriptor} but requested $descriptor")
            }

            ClassModel.FieldModel(
                descriptor ?: existing?.descriptor,
                accessChange.merge(existing?.accessChange ?: AccessChange.NONE),
                mutabilityChange.merge(existing?.mutabilityChange ?: ModifierChange.NONE)
            )
        }
    }

    fun canModifyAccess(name: String) = name in classes

    fun getClassAccess(access: Int, name: String) = classes[name]?.let {
        access.applyVisibilityModifiers(it.accessChange)
    } ?: access

    fun getMethodAccess(access: Int, owner: String, name: String, descriptor: String) = classes[owner]?.method(name, descriptor)?.let {
        access.applyVisibilityModifiers(it.accessChange).applyFinalModifiers(it.extendabilityChange)
    } ?: access

    fun getFieldAccess(access: Int, owner: String, name: String, descriptor: String) = classes[owner]?.fields?.get(name)?.let {
        if(it.descriptor != null && it.descriptor != descriptor) {
            throw UnsupportedOperationException("Expected descriptor ${it.descriptor} according to loaded access modifiers, but got $descriptor")
        }

        access.applyVisibilityModifiers(it.accessChange).applyFinalModifiers(it.mutabilityChange)
    } ?: access

    private fun Int.applyVisibilityModifiers(accessChange: AccessChange) = if (accessChange == AccessChange.NONE) {
        this
    } else {
        // Remove existing visibility flags, add new one
        this and VISIBILITY_MASK.inv() or accessChange.modifier
    }

    private fun Int.applyFinalModifiers(finalChange: ModifierChange) =
        when (finalChange) {
            ModifierChange.REMOVE -> this and Opcodes.ACC_FINAL.inv()
            ModifierChange.ADD -> this or Opcodes.ACC_FINAL
            else -> this
        }

    @Serializable
    data class ClassModel(
        val accessChange: AccessChange = AccessChange.NONE,

        val methods: MutableMap<String, MutableList<MethodModel>> = hashMapOf(),
        val fields: MutableMap<String, FieldModel> = hashMapOf(),
    ) : Intersectable<ClassModel> {
        private val methodSignatures
            get() = methods.flatMapTo(hashSetOf()) { (name, methods) ->
                methods.map { method ->
                    name to method.descriptor
                }
            }

        override fun intersection(other: ClassModel): ClassModel {
            val methodSignatures = methodSignatures.intersect(other.methodSignatures)
            val fieldNames = fields.keys.intersect(other.fields.keys)

            val methods = methodSignatures.groupBy { (name) -> name }.mapValuesTo(hashMapOf()) { (name, signatures) ->
                signatures.mapTo(mutableListOf()) { (_, descriptor) ->
                    findIntersection(method(name, descriptor), other.method(name, descriptor))
                }
            }

            val fields = fieldNames.associateWithTo(hashMapOf()) {
                findIntersection(fields[it], other.fields[it])
            }

            return ClassModel(
                accessChange.intersection(other.accessChange),
                methods,
                fields
            )
        }

        fun method(name: String, descriptor: String) =
            methods[name]?.firstOrNull { it.descriptor == descriptor }

        @Serializable
        data class MethodModel(
            val descriptor: String,

            val accessChange: AccessChange,
            val extendabilityChange: ModifierChange,
        ) : Intersectable<MethodModel> {
            override fun intersection(other: MethodModel) = MethodModel(
                descriptor,
                accessChange.intersection(other.accessChange),
                extendabilityChange.intersection(other.extendabilityChange),
            )
        }

        @Serializable
        data class FieldModel(
            val descriptor: String? = null,

            val accessChange: AccessChange,
            val mutabilityChange: ModifierChange,
        ) : Intersectable<FieldModel> {
            override fun intersection(other: FieldModel): FieldModel {
                if (other.descriptor != null && descriptor != null && other.descriptor != descriptor) {
                    throw UnsupportedOperationException("Field has two defined descriptors, $descriptor and ${other.descriptor} according to access modifiers")
                }

                return FieldModel(
                    descriptor ?: other.descriptor,
                    accessChange.intersection(other.accessChange),
                    mutabilityChange.intersection(other.mutabilityChange),
                )
            }
        }
    }
}
