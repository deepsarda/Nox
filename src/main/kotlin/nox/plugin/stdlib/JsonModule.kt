package nox.plugin.stdlib

import nox.plugin.annotations.NoxDefault
import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.runtime.json.NoxJsonParser
import nox.runtime.json.NoxJsonWriter

/**
 * Nox standard library: `Json` namespace.
 *
 * Provides JSON parsing and serialization as pure data transformations.
 * No permissions required (same as `Math.*`).
 *
 * NSL usage:
 * ```
 * json data = Json.parse("{\"name\": \"Alice\", \"age\": 30}");
 * string text = Json.stringify(data);
 * ```
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "Json")
object JsonModule {
    /**
     * Parse a JSON string into a Nox json value.
     *
     * Integers without decimals become `int`, decimals become `double`.
     * Objects become `json`, arrays become typed or untyped arrays.
     *
     * @throws IllegalArgumentException on malformed JSON (mapped to ParseError in VM)
     */
    @NoxFunction(name = "parse")
    @NoxType("json")
    @JvmStatic
    fun parse(text: String): Any? = NoxJsonParser(text).parse()

    /**
     * Serialize a Nox json value to a JSON string.
     *
     * Pretty-prints by default. Pass `false` for compact output.
     * `NaN` and `Infinity` serialize as `null` per the JSON specification.
     */
    @NoxFunction(name = "stringify")
    @JvmStatic
    fun stringify(
        @NoxType("json") value: Any?,
        @NoxDefault("true") pretty: Boolean = true,
    ): String = NoxJsonWriter(prettyPrint = pretty).write(value)
}
