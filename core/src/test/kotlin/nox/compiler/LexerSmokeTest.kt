package nox.compiler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

/**
 * Smoke test: verifies the ANTLR4-generated classes exist on the classpath.
 * This is the first test that passes after the build is set up.
 */
class LexerSmokeTest :
    FunSpec({

        test("NoxLexer class is generated and loadable") {
            Class.forName("nox.parser.NoxLexer") shouldNotBe null
        }

        test("NoxParser class is generated and loadable") {
            Class.forName("nox.parser.NoxParser") shouldNotBe null
        }

        test("NoxParserVisitor interface is generated and loadable") {
            Class.forName("nox.parser.NoxParserVisitor") shouldNotBe null
        }

        test("NoxParserBaseVisitor class is generated and loadable") {
            Class.forName("nox.parser.NoxParserBaseVisitor") shouldNotBe null
        }
    })
