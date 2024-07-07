package net.msrandom.minecraftcodev.forge.mappings

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import java.net.JarURLConnection
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.notExists
import kotlin.io.path.outputStream

fun injectForgeMappingService(file: Path) =
    zipFileSystem(file).use { zip ->
        val serviceFile = zip.base.getPath("META-INF", "services", "cpw.mods.modlauncher.api.INameMappingService")

        if (serviceFile.notExists()) {
            return false
        }

        val injectsFolder = "/forge-mapping-injects"
        val codevInjects = MinecraftCodevForgePlugin::class.java.getResource(injectsFolder) ?: return false

        serviceFile.deleteExisting()

        val jar = (codevInjects.openConnection() as JarURLConnection).jarFile

        for (entry in jar.entries()) {
            if (entry.isDirectory || !entry.name.startsWith(injectsFolder)) continue

            jar.getInputStream(entry).use {
                it.copyTo(zip.base.getPath(entry.name.substring(injectsFolder.length)).outputStream())
            }
        }

        true
    }
