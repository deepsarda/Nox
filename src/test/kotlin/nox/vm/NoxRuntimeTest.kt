package nox.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.runtime.NoxError
import nox.runtime.NoxResult
import nox.runtime.NoxRuntime

/**
 * Tests for the [NoxRuntime] public facade.
 *
 * Covers builder configuration, compile-and-execute orchestration,
 * error propagation, and resource guard integration.
 */
class NoxRuntimeTest :
    FunSpec({

        val d = '$'

        test("builderCreatesWorkingRuntime") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute("""main() { return "ok"; }""")
            result.shouldBeInstanceOf<NoxResult.Success>()
        }

        test("compilationErrorReturnsErrorResult") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute("this is not valid nox code!!!")
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.CompilationError
            result.message shouldNotBe null
        }

        test("emptySourceReturnsCompilationError") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute("")
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.CompilationError
        }

        test("customFileNameInCompilation") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute("""main() { return "ok"; }""", "custom.nox")
            result.shouldBeInstanceOf<NoxResult.Success>()
        }

        test("yieldsCollectedInSuccessResult") {
            val runtime = NoxRuntime.builder().build()
            val result =
                runtime.execute(
                    """
                    main() {
                        yield "a";
                        yield "b";
                        yield "c";
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.yields shouldContainExactly listOf("a", "b", "c")
        }

        test("noYieldsProducesEmptyList") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute("""main() { return "ok"; }""")
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.yields shouldBe emptyList()
        }

        test("runtimeExceptionYieldsBeforeError") {
            val runtime = NoxRuntime.builder().build()
            val result =
                runtime.execute(
                    """
                    main() {
                        yield "step1";
                        yield "step2";
                        throw "boom";
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.Error
            result.message shouldBe "boom"
            result.yields shouldContainExactly listOf("step1", "step2")
        }

        test("tryCatchDoesNotPropagateToHost") {
            val runtime = NoxRuntime.builder().build()
            val result =
                runtime.execute(
                    """
                    main() {
                        try {
                            throw "handled";
                        } catch (err) {
                            yield err;
                        }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.yields shouldContainExactly listOf("handled")
        }

        test("onYieldCallbackFiringInRealTime") {
            val streamed = mutableListOf<String>()
            val runtime =
                NoxRuntime
                    .builder()
                    .onYield { streamed.add(it) }
                    .build()
            val result =
                runtime.execute(
                    """
                    main() {
                        yield "a";
                        yield "b";
                        yield "c";
                        return "done";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.returnValue shouldBe "done"
            // Both the callback and the result collect yields
            streamed shouldContainExactly listOf("a", "b", "c")
            result.yields shouldContainExactly listOf("a", "b", "c")
        }

        test("onYieldCallbackFiresBeforeError") {
            val streamed = mutableListOf<String>()
            val runtime =
                NoxRuntime
                    .builder()
                    .onYield { streamed.add(it) }
                    .build()
            val result =
                runtime.execute(
                    """
                    main() {
                        yield "before";
                        throw "boom";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Error>()
            streamed shouldContainExactly listOf("before")
        }

        test("executeWithProvidedArgsMapsToVM") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute(
                """
                main(int count, string name) {
                    return `Hello ${'$'}{name} ${'$'}{count} times`;
                }
                """.trimIndent(),
                args = mapOf("count" to 5, "name" to "Alice")
            )
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.returnValue shouldBe "Hello Alice 5 times"
        }

        test("executeWithMissingRequiredArgReturnsError") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute(
                """
                main(int count) {
                    return count.toString();
                }
                """.trimIndent(),
                args = emptyMap()
            )
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.Error
            result.message shouldBe "Missing required argument: 'count'"
        }

        test("executeUsesDefaultValueWhenArgIsMissing") {
            val runtime = NoxRuntime.builder().build()
            val result = runtime.execute(
                """
                main(int count = 10) {
                    return count.toString();
                }
                """.trimIndent(),
                args = emptyMap()
            )
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.returnValue shouldBe "10"
        }
    })
