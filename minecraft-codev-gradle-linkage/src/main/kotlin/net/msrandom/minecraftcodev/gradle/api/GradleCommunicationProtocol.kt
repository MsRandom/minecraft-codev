package net.msrandom.minecraftcodev.gradle.api

import javax.inject.Inject

typealias GradleSerializedData = List<Any>

interface GradleSerializable {
    fun serialize(serializer: GradleCommunicationProtocol.Serializer)
}

object GradleCommunicationProtocol {
    class Serializer {
        private val objects = mutableListOf<Any>()

        val data: GradleSerializedData get() = objects

        fun put(any: Any) {
            objects.add(any)
        }

        fun <T : GradleSerializable> putList(list: List<T>) {
            put(list.size)

            for (value in list) {
                value.serialize(this)
            }
        }
    }

    open class Deserializer
    @Inject
    constructor(data: GradleSerializedData) {
        private val data = data.toMutableList()

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> get() = data.removeFirst() as T

        fun <T> getList(deserialize: (Deserializer) -> T) =
            List(get()) {
                deserialize(this)
            }
    }
}
