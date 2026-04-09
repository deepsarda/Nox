package nox.plugin.external

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.plugin.LibraryRegistry
import nox.runtime.NoxResult
import nox.runtime.NoxRuntime
import java.io.File

class ExternalPluginBridgeTest :
    FunSpec({

        test("loading and executing C plugin") {
            val libName =
                if (System.getProperty("os.name").lowercase().contains("mac")) {
                    "libtest_plugin.dylib"
                } else if (System.getProperty("os.name").lowercase().contains("windows")) {
                    "test_plugin.dll"
                } else {
                    "libtest_plugin.so"
                }

            val libPath = System.getProperty("test.plugin.path") ?: "src/test/c/$libName"
            val file = File(libPath)

            if (!file.exists()) {
                println("C plugin shared library not found at ${file.absolutePath}. Skipping test.")
            } else {
                val registry = LibraryRegistry.createDefault()
                ExternalPluginBridge.loadPlugin(file.absolutePath, registry)

                registry.isBuiltinNamespace("test_c") shouldBe true
                registry.lookupNamespaceFunc("test_c", "add_ints") shouldNotBe null

                val source =
                    """
                    main() {
                        int sum_i = test_c.add_ints(10, 20);
                        double sum_d = test_c.add_doubles(5.5, 4.5);
                        boolean inv = test_c.logical_not(true);
                        string msg = test_c.greet("Nox");
                        return `${'$'}{sum_i},${'$'}{sum_d},${'$'}{inv},${'$'}{msg}`;
                    }
                    """.trimIndent()
                val runtime =
                    NoxRuntime
                        .builder()
                        .withRegistry(registry)
                        .build()

                val result = runtime.execute(source)
                if (result is NoxResult.Error) {
                    println("Execution failed: ${result.message}")
                }

                result.shouldBeInstanceOf<NoxResult.Success>()
                result.returnValue shouldBe "30,10,false,Hello from C, Nox"

                result.yields.contains("yielding from C!") shouldBe true
            }
        }
    })
