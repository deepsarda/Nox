package nox.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.NoxCompiler
import nox.runtime.NoxResult
import nox.runtime.NoxRuntime
import java.nio.file.Files
import java.nio.file.Path

class GoldenTest :
    FunSpec({

        val generateGoldens = System.getenv("GENERATE_GOLDENS") == "true"

        val resourcesDir = Path.of("src/test/resources/nox")
        val programsDir = resourcesDir.resolve("programs")
        val errorsDir = resourcesDir.resolve("errors")
        val expectedDir = resourcesDir.resolve("expected")

        fun findNoxFiles(dir: Path): List<Path> {
            if (!Files.exists(dir)) return emptyList()
            return Files.walk(dir).filter { it.toString().endsWith(".nox") }.toList()
        }

        val allFiles = findNoxFiles(programsDir) + findNoxFiles(errorsDir)

        allFiles.forEach { noxFile ->

            val relativePath = resourcesDir.relativize(noxFile).toString()

            test("Golden Test: $relativePath") {
                val source = Files.readString(noxFile)

                // Parse pragmas
                var expectedResult: String? = null
                var expectedError: String? = null
                var expectedErrorContains: String? = null
                var expectedYields: String? =
                    null // For simplicity, we can treat yields as a string representation of the list
                val args = mutableMapOf<String, Any?>()

                source.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("// @test:result")) {
                        expectedResult = trimmed.substringAfter("// @test:result").trim().removeSurrounding("\"")
                    } else if (trimmed.startsWith("// @test:errorContains")) {
                        expectedErrorContains =
                            trimmed.substringAfter("// @test:errorContains").trim().removeSurrounding("\"")
                    } else if (trimmed.startsWith("// @test:error")) {
                        expectedError = trimmed.substringAfter("// @test:error").trim().removeSurrounding("\"")
                    } else if (trimmed.startsWith("// @test:yields")) {
                        expectedYields = trimmed.substringAfter("// @test:yields").trim()
                    } else if (trimmed.startsWith("// @test:arg")) {
                        val parts = trimmed.substringAfter("// @test:arg").trim().split(" ", limit = 2)
                        if (parts.size == 2) {
                            val name = parts[0].trim()
                            val valueStr = parts[1].trim()
                            // Try to parse as JSON if it looks like it, else keep as string
                            val value =
                                try {
                                    nox.runtime.json
                                        .NoxJsonParser(valueStr)
                                        .parse()
                                } catch (e: Exception) {
                                    valueStr.removeSurrounding("\"")
                                }
                            args[name] = value
                        }
                    }
                }

                // 1. Snapshot Test (Compiler Disassembly)
                val compileResult = NoxCompiler.compile(source, noxFile.fileName.toString(), noxFile.toAbsolutePath())

                val actualNoxc = compileResult.disassembly

                if (actualNoxc != null) {
                    // To avoid timestamp differences in diffing, we can strip the 'Compiled: ...' line
                    val normalizedNoxc = actualNoxc.lines().filter { !it.startsWith(";  Compiled:") }.joinToString("\n")

                    val expectedNoxcFile = expectedDir.resolve(noxFile.fileName.toString().replace(".nox", ".noxc"))

                    if (generateGoldens) {
                        Files.createDirectories(expectedNoxcFile.parent)
                        Files.writeString(expectedNoxcFile, normalizedNoxc)
                    } else {
                        if (Files.exists(expectedNoxcFile)) {
                            val expectedNoxc = Files.readString(expectedNoxcFile)
                            normalizedNoxc shouldBe expectedNoxc
                        } else {
                            throw AssertionError(
                                "Missing golden file: $expectedNoxcFile. Run with GENERATE_GOLDENS=true to create it.",
                            )
                        }
                    }
                }

                // 2. E2E Execution Test
                val runtime =
                    NoxRuntime
                        .builder()
                        .setPermissionHandler { nox.runtime.PermissionResponse.Granted.Unconstrained }
                        .build()
                val result =
                    runtime.execute(
                        source,
                        noxFile.fileName.toString(),
                        args,
                        basePath = noxFile.toAbsolutePath(),
                    )

                when (result) {
                    is NoxResult.Success -> {
                        println(result.yields)
                        if (expectedError != null || expectedErrorContains != null) {
                            throw AssertionError(
                                "Expected error but execution succeeded with result: ${result.returnValue}",
                            )
                        }
                        if (expectedResult != null) {
                            result.returnValue shouldBe expectedResult
                        }
                        if (expectedYields != null) {
                            result.yields.toString() shouldBe expectedYields
                        }
                    }

                    is NoxResult.Error -> {
                        println(result.yields)
                        if (expectedResult != null) {
                            println("TEST FAILED. Type: ${result.type}, Message: ${result.message}")
                            throw AssertionError(
                                "Expected success with result '$expectedResult' but failed with error: ${result.type} - ${result.message}",
                            )
                        }
                        if (expectedError != null) {
                            result.type.name shouldBe expectedError
                        }
                        if (expectedErrorContains != null) {
                            result.message.orEmpty() shouldContain expectedErrorContains
                        }
                    }
                }
            }
        }
    })
