package nox.format

import io.kotest.core.spec.style.StringSpec
import java.io.File

class FormatterGoldenTest :
    StringSpec({
        val inputDir =
            File("src/test/resources/nox/format/input").absoluteFile.let {
                if (it.exists()) it else File("nox-format/src/test/resources/nox/format/input").absoluteFile
            }
        val expectedDir =
            File("src/test/resources/nox/format/expected").absoluteFile.let {
                if (it.exists()) it else File("nox-format/src/test/resources/nox/format/expected").absoluteFile
            }

        if (inputDir.exists()) {
            inputDir.walkTopDown().filter { it.isFile && it.extension == "nox" }.forEach { file ->
                "formats ${file.name} to match expected output" {
                    val inputContent = file.readText()
                    val expectedFile = File(expectedDir, file.name)
                    val expectedContent = if (expectedFile.exists()) expectedFile.readText() else ""

                    val formatted = Formatter.format(inputContent)

                    // Not GENERATE_GOLDENS to allow for fine grained control.  This allows us to update snapshots for the formatter without accidentally updating compiler snapshots.
                    if (System.getenv("UPDATE_SNAPSHOTS") == "true" || !expectedFile.exists()) {
                        expectedFile.parentFile.mkdirs()
                        expectedFile.writeText(formatted)
                        println("Updated snapshot for ${file.name}")
                    } else {
                        if (formatted != expectedContent) {
                            val cLines = expectedContent.split('\n')
                            val fLines = formatted.split('\n')
                            val diff = mutableListOf<String>()
                            diff.add("---" + file.name)
                            diff.add("+++" + file.name)
                            for (i in 0 until maxOf(cLines.size, fLines.size)) {
                                val c = cLines.getOrNull(i)
                                val f = fLines.getOrNull(i)
                                if (c != f) {
                                    if (c != null) diff.add("- " + c.replace("\r", ""))
                                    if (f != null) diff.add("+ " + f.replace("\r", ""))
                                } else {
                                    if (c != null) diff.add("  " + c.replace("\r", ""))
                                }
                            }
                            val diffStr = diff.joinToString("\n")
                            println(diffStr)
                            throw AssertionError("Formatting mismatch for ${file.name}:\n$diffStr")
                        }
                    }
                }
            }
        }
    })
