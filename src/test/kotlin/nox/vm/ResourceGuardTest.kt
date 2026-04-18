package nox.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.runtime.*
import java.time.Duration

class ResourceGuardTest :
    FunSpec({

        val d = '$'

        test("instructionQuotaDeniedReturnsError") {
            val runtime =
                NoxRuntime
                    .builder()
                    .maxInstructions(10)
                    .setResourceHandler { request ->
                        when (request) {
                            is ResourceRequest.InstructionQuota -> ResourceResponse.Denied("Too many instructions")
                            else -> ResourceResponse.Denied()
                        }
                    }.build()
            val result =
                runtime.execute(
                    """
                    main() {
                        int x = 0;
                        while (x < 1000) { x = x + 1; }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.QuotaExceededError
        }

        test("instructionQuotaGrantedAllowsContinuation") {
            var grantCount = 0
            val runtime =
                NoxRuntime
                    .builder()
                    .maxInstructions(50)
                    .setResourceHandler { request ->
                        when (request) {
                            is ResourceRequest.InstructionQuota -> {
                                grantCount++
                                ResourceResponse.Granted(request.currentLimit + 1_000)
                            }
                            else -> ResourceResponse.Granted(Long.MAX_VALUE)
                        }
                    }.build()
            val result =
                runtime.execute(
                    """
                    main() {
                        int x = 0;
                        while (x < 50) { x = x + 1; }
                        yield "done";
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.yields shouldContainExactly listOf("done")
            grantCount shouldNotBe 0
        }

        test("catch-all does not catch QuotaExceededError") {
            val source = """
            main() {
                try {
                    int x = 0;
                    while (true) {
                        x = x + 1;
                    }
                } catch (err) {
                    yield "wrong. Catch all should not catch resource limits.";
                }
                return "ok";
            }
        """
            val runtime =
                NoxRuntime
                    .builder()
                    .maxInstructions(200)
                    .maxExecutionTime(Duration.ofSeconds(10))
                    .build()

            val result = runtime.execute(source)
            result.shouldBeInstanceOf<NoxResult.Error>()
            // The error should bubble up as an unhandled exception, caught by the execute() method.
            // NoxRuntime handles it and wraps it into NoxResult.Error
            result.type shouldBe NoxError.QuotaExceededError
        }

        test("explicit catch of QuotaExceededError works") {
            val source = """
            main() {
                try {
                    int x = 0;
                    while (true) {
                        x = x + 1;
                    }
                } catch (err) {
                    yield "wrong";
                } catch (QuotaExceededError err) {
                    yield "ok";
                }
                return "done";
            }
        """
            val runtime =
                NoxRuntime
                    .builder()
                    .maxInstructions(200)
                    .maxExecutionTime(Duration.ofSeconds(10))
                    .build()

            val result = runtime.execute(source)
            (result as NoxResult.Success).yields shouldContainExactly listOf("ok")
        }

        test("callDepthDeniedReturnsStackOverflow") {
            val runtime =
                NoxRuntime
                    .builder()
                    .maxCallDepth(5)
                    .setResourceHandler { request ->
                        when (request) {
                            is ResourceRequest.CallDepth -> ResourceResponse.Denied("Stack limit")
                            else -> ResourceResponse.Granted(Long.MAX_VALUE)
                        }
                    }.build()
            val result =
                runtime.execute(
                    """
                    void recurse(int n) {
                        if (n > 0) { recurse(n - 1); }
                    }
                    main() {
                        recurse(100);
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.StackOverflowError
        }

        test("callDepthGrantedExtendsLimit") {
            val runtime =
                NoxRuntime
                    .builder()
                    .maxCallDepth(5)
                    .setResourceHandler { request ->
                        when (request) {
                            is ResourceRequest.CallDepth -> ResourceResponse.Granted(1024)
                            else -> ResourceResponse.Granted(Long.MAX_VALUE)
                        }
                    }.build()
            val result =
                runtime.execute(
                    """
                    int fib(int n) {
                        if (n <= 1) { return n; }
                        return fib(n - 1) + fib(n - 2);
                    }
                    main() {
                        int x = fib(10);
                        yield `$d{x}`;
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Success>()
            result.yields shouldContainExactly listOf("55")
        }

        test("catch-all does not catch StackOverflowError") {
            val source = """
            void recurse() {
                recurse();
            }
            main() {
                try {
                    recurse();
                } catch (err) {
                    yield "wrong. Catch all should not catch resource limits.";
                }
                return "ok";
            }
        """
            val runtime =
                NoxRuntime
                    .builder()
                    .maxCallDepth(10)
                    .maxInstructions(1_000_000)
                    .maxExecutionTime(Duration.ofSeconds(10))
                    .build()

            val result = runtime.execute(source)
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.StackOverflowError
        }

        test("explicit catch of StackOverflowError works") {
            val source = """
            void recurse() {
                recurse();
            }
            main() {
                try {
                    recurse();
                } catch (err) {
                    yield "wrong";
                } catch (StackOverflowError err) {
                    yield "ok";
                }
                return "done";
            }
        """
            val runtime =
                NoxRuntime
                    .builder()
                    .maxCallDepth(10)
                    .maxInstructions(1_000_000)
                    .maxExecutionTime(Duration.ofSeconds(10))
                    .build()

            val result = runtime.execute(source)
            (result as NoxResult.Success).yields shouldContainExactly listOf("ok")
        }

        test("timeoutDeniedReturnsTimeoutError") {

            val runtime =
                NoxRuntime
                    .builder()
                    .maxExecutionTime(Duration.ofMillis(20))
                    .maxInstructions(Long.MAX_VALUE)
                    .setResourceHandler { request ->
                        when (request) {
                            is ResourceRequest.ExecutionTimeout ->
                                ResourceResponse.Denied("Timed out")
                            else -> ResourceResponse.Granted(Long.MAX_VALUE)
                        }
                    }.build()
            val result =
                runtime.execute(
                    """
                    main() {
                        int x = 0;
                        while (true) { x = x + 1; }
                        return "ok";
                    }
                    """.trimIndent(),
                )
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.TimeoutError
        }

        test("catch-all does not catch TimeoutError") {
            val source = """
            main() {
                try {
                    while (true) {
                        // Busy wait
                    }
                } catch (err) {
                    yield "wrong. Catch all should not catch resource limits.";
                }
                return "ok";
            }
        """
            val runtime =
                NoxRuntime
                    .builder()
                    // We give it a generous instruction limit so it hits the timeout
                    .maxInstructions(10_000_000)
                    .maxExecutionTime(Duration.ofMillis(100))
                    .build()

            val result = runtime.execute(source)
            result.shouldBeInstanceOf<NoxResult.Error>()
            result.type shouldBe NoxError.TimeoutError
        }

        test("explicit catch of TimeoutError works") {
            val source = """
            main() {
                try {
                    while (true) {
                        // Busy wait
                    }
                } catch (err) {
                    yield "wrong";
                } catch (TimeoutError err) {
                    yield "ok";
                }
                return "done";
            }
        """
            val runtime =
                NoxRuntime
                    .builder()
                    .maxInstructions(10_000_000)
                    .maxExecutionTime(Duration.ofMillis(100))
                    .build()

            val result = runtime.execute(source)
            (result as NoxResult.Success).yields shouldContainExactly listOf("ok")
        }
    })
