package net.msrandom.minecraftcodev.accesswidener

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.serialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerVisitor
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.ModifierChange
import org.objectweb.asm.Opcodes

private const val VISIBILITY_MASK = Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED

@Serializable
data class AccessModifiers(
    @Transient
    private val onlyTransitives: Boolean = false,
    var namespace: String? = null,
    val classes: MutableMap<String, ClassModel> = hashMapOf(),
) : AccessWidenerVisitor {
    fun onlyTransitives() = copy(onlyTransitives = true)

    fun visit(modifiers: AccessModifiers) {
        visitHeader(modifiers.namespace)

        for ((className, classModel) in modifiers.classes) {
            visitClass(className, classModel.accessTransform)

            for ((methodName, methodModels) in classModel.methods) {
                for (methodModel in methodModels) {
                    visitMethod(className, methodName, methodModel.descriptor, methodModel.accessTransform)
                }
            }

            for ((fieldName, fieldModel) in classModel.fields) {
                visitField(className, fieldName, fieldModel.descriptor, fieldModel.accessTransform)
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

    override fun visitField(
        owner: String,
        name: String,
        descriptor: String?,
        access: AccessWidenerReader.AccessType,
        transitive: Boolean,
    ) {
        if (!shouldVisit(transitive)) return

        val accessTransform =
            when (access) {
                AccessWidenerReader.AccessType.ACCESSIBLE -> AccessTransform.PUBLIC
                AccessWidenerReader.AccessType.MUTABLE -> AccessTransform.of(ModifierChange.REMOVE)
                else -> AccessTransform.EMPTY
            }

        if (accessTransform != AccessTransform.EMPTY) {
            visitField(owner, name, descriptor, accessTransform)
        }
    }

    override fun visitMethod(
        owner: String,
        name: String,
        descriptor: String,
        access: AccessWidenerReader.AccessType,
        transitive: Boolean,
    ) {
        if (!shouldVisit(transitive)) return

        val accessTransform =
            when (access) {
                AccessWidenerReader.AccessType.ACCESSIBLE -> AccessTransform.PUBLIC
                AccessWidenerReader.AccessType.EXTENDABLE -> AccessTransform.of(AccessChange.PROTECTED, ModifierChange.REMOVE)
                else -> AccessTransform.EMPTY
            }

        if (accessTransform != AccessTransform.EMPTY) {
            visitMethod(owner, name, descriptor, accessTransform)
        }
    }

    override fun visitClass(
        name: String,
        access: AccessWidenerReader.AccessType,
        transitive: Boolean,
    ) {
        if (!shouldVisit(transitive)) return

        val accessTransform =
            when (access) {
                AccessWidenerReader.AccessType.ACCESSIBLE -> AccessTransform.PUBLIC
                AccessWidenerReader.AccessType.EXTENDABLE -> AccessTransform.of(AccessChange.PROTECTED, ModifierChange.REMOVE)
                else -> AccessTransform.EMPTY
            }

        if (accessTransform != AccessTransform.EMPTY) {
            visitClass(name, accessTransform)
        }
    }

    fun visitClass(
        name: String,
        accessTransform: AccessTransform,
    ) {
        var nestedClassSeparatorIndex = name.indexOf('$')

        while (nestedClassSeparatorIndex != -1) {
            classes.computeIfAbsent(name.substring(0, nestedClassSeparatorIndex)) { ClassModel() }

            nestedClassSeparatorIndex = name.indexOf('$', nestedClassSeparatorIndex + 1)
        }

        classes.compute(name) { _, existing ->
            if (existing == null) {
                ClassModel(accessTransform)
            } else {
                ClassModel(
                    accessTransform.merge(existing.accessTransform),
                    existing.methods,
                    existing.fields,
                )
            }
        }
    }

    fun visitMethod(
        owner: String,
        name: String,
        descriptor: String,
        accessTransform: AccessTransform,
    ) {
        val classModel = classes.computeIfAbsent(owner) { ClassModel() }

        var existingMethod = classModel.method(name, descriptor)

        if (existingMethod == null) {
            existingMethod = ClassModel.MethodModel(descriptor, accessTransform)

            classModel.methods.computeIfAbsent(name) { mutableListOf() }.add(existingMethod)
        } else {
            val group = classModel.methods.getValue(name)

            group.remove(existingMethod)

            group.add(
                ClassModel.MethodModel(
                    descriptor,
                    accessTransform.merge(existingMethod.accessTransform),
                ),
            )
        }
    }

    fun visitField(
        owner: String,
        name: String,
        descriptor: String?,
        accessTransform: AccessTransform,
    ) {
        val classModel = classes.computeIfAbsent(owner) { ClassModel() }

        classModel.fields.compute(name) { _, existing ->
            if (existing?.descriptor != null && descriptor != null && existing.descriptor != descriptor) {
                throw UnsupportedOperationException(
                    "Field $owner.$name has two defined descriptors, previously ${existing.descriptor} but requested $descriptor",
                )
            }

            ClassModel.FieldModel(
                descriptor ?: existing?.descriptor,
                accessTransform.merge(existing?.accessTransform ?: AccessTransform.EMPTY),
            )
        }
    }

    fun canModifyAccess(name: String) = name in classes

    fun getClassAccess(
        access: Int,
        name: String,
    ) = classes[name]?.let {
        access.applyVisibilityModifiers(it.accessTransform.access).applyFinalModifiers(it.accessTransform.final)
    } ?: access

    fun getMethodAccess(
        access: Int,
        owner: String,
        name: String,
        descriptor: String,
    ) = classes[owner]?.method(name, descriptor)?.let {
        access.applyVisibilityModifiers(it.accessTransform.access).applyFinalModifiers(it.accessTransform.final)
    } ?: access

    fun getFieldAccess(
        access: Int,
        owner: String,
        name: String,
        descriptor: String,
    ) = classes[owner]?.fields?.get(name)?.let {
        if (it.descriptor != null && it.descriptor != descriptor) {
            throw UnsupportedOperationException(
                "Expected descriptor ${it.descriptor} according to loaded access modifiers, but got $descriptor",
            )
        }

        access.applyVisibilityModifiers(it.accessTransform.access).applyFinalModifiers(it.accessTransform.final)
    } ?: access

    private fun Int.applyVisibilityModifiers(accessChange: AccessChange) =
        if (accessChange == AccessChange.NONE) {
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
        @Serializable(AccessTransformSerializer::class) val accessTransform: AccessTransform = AccessTransform.EMPTY,
        val methods: MutableMap<String, MutableList<MethodModel>> = hashMapOf(),
        val fields: MutableMap<String, FieldModel> = hashMapOf(),
    ) {
        private val methodSignatures
            get() =
                methods.flatMapTo(hashSetOf()) { (name, methods) ->
                    methods.map { method ->
                        name to method.descriptor
                    }
                }

        fun method(
            name: String,
            descriptor: String,
        ) = methods[name]?.firstOrNull { it.descriptor == descriptor }

        @Serializable
        data class MethodModel(
            val descriptor: String,
            @Serializable(AccessTransformSerializer::class) val accessTransform: AccessTransform,
        )

        @Serializable
        data class FieldModel(
            val descriptor: String? = null,
            @Serializable(AccessTransformSerializer::class) val accessTransform: AccessTransform,
        )
    }

    class AccessTransformSerializer : KSerializer<AccessTransform> {
        @OptIn(InternalSerializationApi::class)
        override val descriptor: SerialDescriptor
            get() = buildClassSerialDescriptor(AccessTransform::class.qualifiedName!!) {
                element<AccessChange>("accessChange")
                element<ModifierChange>("finalChange")
            }

        override fun serialize(
            encoder: Encoder,
            value: AccessTransform
        ) = encoder.encodeStructure(descriptor) {
            encodeSerializableElement(serialDescriptor<AccessChange>(), 0, kotlinx.serialization.serializer(), value.access)
            encodeSerializableElement(serialDescriptor<ModifierChange>(), 1, kotlinx.serialization.serializer(), value.final)
        }

        override fun deserialize(decoder: Decoder): AccessTransform = decoder.decodeStructure(descriptor) {
            AccessTransform.of(
                decodeSerializableElement(serialDescriptor<AccessChange>(), 0, kotlinx.serialization.serializer()),
                decodeSerializableElement(serialDescriptor<ModifierChange>(), 1, kotlinx.serialization.serializer()),
            )
        }
    }
}
