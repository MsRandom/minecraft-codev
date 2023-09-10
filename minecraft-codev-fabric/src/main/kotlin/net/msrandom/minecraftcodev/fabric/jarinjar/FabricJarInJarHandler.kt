package net.msrandom.minecraftcodev.fabric.jarinjar

import kotlinx.serialization.json.*
import net.msrandom.minecraftcodev.core.ListedFileHandler
import net.msrandom.minecraftcodev.includes.IncludedJar
import java.nio.file.Path
import kotlin.io.path.outputStream

class FabricJarInJarHandler(jars: JsonArray, private val json: JsonObject, private val jsonName: String) : ListedFileHandler<IncludedJar> {
    private val jars = jars.map {
        val path = it.jsonObject["file"]?.jsonPrimitive?.content!!

        val fileName = path.substringAfterLast('/')
        val name = fileName.substringBeforeLast('.')
        val parts = name.split('-')

        val versionPartIndex = parts.indexOfFirst { it.firstOrNull()?.isDigit() == true }

        if (versionPartIndex < 0) {
            if (parts.size > 1) {
                val artifact = parts.subList(0, parts.lastIndex).joinToString("-")
                val classifier = parts.last()

                IncludedJar(
                    path,
                    fileName,
                    null,
                    artifact,
                    null,
                    null,
                    classifier
                )
            } else {
                val artifact = parts.first()

                IncludedJar(
                    path,
                    fileName,
                    null,
                    artifact,
                    null,
                    null,
                    null
                )
            }
        } else {
            val afterVersion = parts.subList(versionPartIndex, parts.size)

            val classifierIndex = afterVersion.indexOfFirst { it.firstOrNull()?.isDigit() == false }

            val artifact = parts.subList(0, versionPartIndex).joinToString("-")
            if (classifierIndex < 0) {
                val version = afterVersion.subList(0, afterVersion.size).joinToString("-")

                IncludedJar(
                    path,
                    fileName,
                    null,
                    artifact,
                    version,
                    version,
                    null
                )
            } else {
                val version = afterVersion.subList(0, classifierIndex).joinToString("-")
                val classifier = afterVersion.subList(classifierIndex + 1, afterVersion.size).joinToString("-")

                IncludedJar(
                    path,
                    fileName,
                    null,
                    artifact,
                    version,
                    version,
                    classifier
                )
            }
        }
    }

    override fun list(root: Path) = jars

    override fun remove(root: Path) {
        root.resolve(jsonName).outputStream().use { output ->
            Companion.json.encodeToStream(JsonObject(json.filterNot { it.key == "jars" }), output)
        }
    }

    companion object {
        private val json = Json { prettyPrint = true }
    }
}
