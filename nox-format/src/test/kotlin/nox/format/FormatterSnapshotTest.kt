package nox.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FormatterSnapshotTest :
    StringSpec({
        "reformats ugly input into canonical style" {
            val ugly =
                """
                main(  ){
                int   x=1;
                if(x==1){return;}else{x=x+1;}
                }
                """.trimIndent()
            val expected =
                """
                main() {
                    int x = 1;
                    if (x == 1) {
                        return;
                    } else {
                        x = x + 1;
                    }
                }
                """.trimIndent() + "\n"
            Formatter.format(ugly) shouldBe expected
        }

        "preserves line comments" {
            val source =
                """
                // top-of-file
                main() {
                    int x = 1; // trailing
                    // standalone
                    return;
                }
                """.trimIndent() + "\n"
            val formatted = Formatter.format(source)
            formatted.contains("// top-of-file") shouldBe true
            formatted.contains("// trailing") shouldBe true
            formatted.contains("// standalone") shouldBe true
        }

        "formats template literals verbatim" {
            val source =
                """
                main() {
                    string name = "world";
                    yield `Hello, ${'$'}{name}!`;
                }
                """.trimIndent() + "\n"
            val formatted = Formatter.format(source)
            formatted.contains("`Hello, \${name}!`") shouldBe true
        }

        "leaves syntactically invalid input untouched" {
            val broken = "main( {"
            Formatter.format(broken) shouldBe broken
        }
    })
