package net.msrandom.minecraftcodev.accesswidener

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.accesswidener.AccessWidenerReader
import net.msrandom.minecraftcodev.core.ResolutionData
import net.msrandom.minecraftcodev.core.resolutionRules
import net.msrandom.minecraftcodev.core.zipResolutionRules
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.hash.HashCode
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.inputStream

class AccessModifierResolutionData(
    visitor: AccessModifiers,
    messageDigest: MessageDigest,
    val namespace: String?,
) : ResolutionData<AccessModifiers>(visitor, messageDigest)

open class AccessWidenerExtension(objectFactory: ObjectFactory, private val project: Project) {
    val zipAccessWidenerResolution = objectFactory.zipResolutionRules<AccessModifierResolutionData>()
    val accessWidenerResolution = objectFactory.resolutionRules(zipAccessWidenerResolution)

    private val accessWidenerCache = ConcurrentHashMap<Configuration, LoadedAccessWideners>()

    init {
        accessWidenerResolution.add { path, extension, data ->
            if (extension.lowercase() != "accesswidener") {
                return@add false
            }

            val reader = AccessWidenerReader(data.visitor)

            data.decorate(path.inputStream()).bufferedReader().use {
                reader.read(it, data.namespace)
            }

            true
        }

        accessWidenerResolution.add { path, extension, data ->
            if (extension.lowercase() != "json") {
                return@add false
            }

            val modifiers =
                data.decorate(path.inputStream()).use {
                    Json.decodeFromStream<AccessModifiers>(it)
                }

            data.visitor.visit(modifiers)

            true
        }
    }

    fun loadAccessWideners(
        files: FileCollection,
        namespace: String?,
    ): LoadedAccessWideners {
        val widener = AccessModifiers(false, namespace)

        val md = MessageDigest.getInstance("SHA1")

        val data = AccessModifierResolutionData(widener, md, namespace)

        for (file in files) {
            for (rule in accessWidenerResolution.get()) {
                if (rule.load(file.toPath(), file.extension, data)) {
                    break
                }
            }
        }

        return LoadedAccessWideners(
            widener,
            HashCode.fromBytes(md.digest()),
        )
    }

    data class LoadedAccessWideners(val tree: AccessModifiers, val hash: HashCode)
}
