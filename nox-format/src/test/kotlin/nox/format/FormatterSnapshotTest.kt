package nox.format

import io.kotest.core.spec.style.StringSpec
import java.io.File

class FormatterSnapshotTest :
    StringSpec({
        val targetDir =
            File("src/test/resources/nox").absoluteFile.let {
                if (it.exists()) it else File("../src/test/resources/nox").absoluteFile
            }

        if (targetDir.exists()) {
            targetDir
                .walkTopDown()
                .filter {
                    it.isFile &&
                        it.extension == "nox" &&
                        !it.absolutePath.contains(
                            "/format/",
                        )
                }.forEach { file ->
                    "formats ${file.name} to its golden representation" {
                        val content = file.readText()
                        val formatted = Formatter.format(content)
                        if (content != formatted) {
                            val cLines = content.split('\n')
                            val fLines = formatted.split('\n')
                            val diff = mutableListOf<String>()
                            diff.add("--- a/${file.name}")
                            diff.add("+++ b/${file.name}")
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
    })
