package nox.plugin.external

/**
 * Type tags for Tier 1 external plugin function parameters and return values.
 *
 * These mirror the C ABI type tags from `plugin_contract.h`:
 * ```c
 * // INT=0, DOUBLE=1, BOOL=2, STRING=3, JSON=4, VOID=5, ...
 * ```
 *
 * See docs/extensibility/plugin-guide.md.
 */
enum class NoxTypeTag {
    INT,
    DOUBLE,
    BOOLEAN,
    STRING,
    JSON,
    VOID,
    STRING_ARRAY,
    INT_ARRAY,
    DOUBLE_ARRAY,
}

/**
 * Describes a single function exported by a Tier 1 external plugin.
 *
 * @property name       function name visible to NSL
 * @property paramTypes parameter type tags, in order
 * @property returnType return value type tag
 */
data class NoxExternalFunc(
    val name: String,
    val paramTypes: List<NoxTypeTag>,
    val returnType: NoxTypeTag,
)

/**
 * Manifest for a Tier 1 external plugin (shared library loaded via C ABI).
 *
 * Kotlin-side mirror of the C `NoxPluginManifest` struct. Created by the
 * plugin loader after calling `nox_plugin_init()` on the shared library.
 *
 * @property namespace the NSL namespace for all functions in this plugin
 * @property functions list of exported function descriptors
 */
data class NoxPluginManifest(
    val namespace: String,
    val functions: List<NoxExternalFunc>,
)
