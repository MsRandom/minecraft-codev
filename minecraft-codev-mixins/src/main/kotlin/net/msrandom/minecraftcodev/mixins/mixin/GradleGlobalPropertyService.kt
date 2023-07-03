package net.msrandom.minecraftcodev.mixins.mixin

import org.spongepowered.asm.service.IGlobalPropertyService
import org.spongepowered.asm.service.IPropertyKey

class GradleGlobalPropertyService : IGlobalPropertyService {
    private val data = hashMapOf<Any?, Any?>()

    override fun resolveKey(name: String): IPropertyKey = PropertyKey(name)

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(key: IPropertyKey) = data[key] as? T

    override fun <T> getProperty(key: IPropertyKey, defaultValue: T) = getProperty(key) ?: defaultValue

    override fun setProperty(key: IPropertyKey, value: Any?) {
        data[key] = value
    }

    override fun getPropertyString(key: IPropertyKey, defaultValue: String) = getProperty<Any?>(key)?.toString() ?: defaultValue

    private data class PropertyKey(val name: String) : IPropertyKey
}
