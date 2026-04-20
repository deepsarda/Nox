package nox.cli

import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.terminal.Terminal
import nox.plugin.LibraryRegistry
import nox.plugin.external.ExternalPluginBridge
import java.io.File

/**
 * Loads C plugins from file paths into the registry.
 */
object PluginLoader {
    fun loadAll(
        paths: List<String>,
        registry: LibraryRegistry,
        terminal: Terminal,
    ) {
        for (path in paths) {
            val file = File(path)
            if (!file.exists()) {
                terminal.println(red("Plugin not found: $path"))
                continue
            }
            try {
                ExternalPluginBridge.loadPlugin(file.absolutePath, registry)
                terminal.println(green("Loaded plugin: ${file.name}"))
            } catch (e: Exception) {
                terminal.println(red("Failed to load plugin $path: ${e.message}"))
            }
        }
    }
}
