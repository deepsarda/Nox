package nox.plugin

/**
 * Interface implemented by KSP-generated registry classes.
 * Discovered at runtime via ServiceLoader.
 */
interface PluginRegistryProvider {
    fun registerAll(registry: LibraryRegistry)
}
