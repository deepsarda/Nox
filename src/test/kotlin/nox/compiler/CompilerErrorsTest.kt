package nox.compiler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import nox.compiler.ast.SourceLocation

class CompilerErrorsTest :
    FunSpec({

        test("records and formats errors correctly") {
            val errors = CompilerErrors()
            errors.hasErrors() shouldBe false
            errors.count shouldBe 0
            errors.all() shouldHaveSize 0

            val loc1 = SourceLocation("test.nox", 10, 5)
            errors.report(loc1, "First error")

            val loc2 = SourceLocation("test.nox", 20, 8)
            errors.report(loc2, "Second error", suggestion = "Fix it")

            errors.hasErrors() shouldBe true
            errors.count shouldBe 2
            errors.all() shouldHaveSize 2

            val allErrors = errors.all()
            allErrors[0].message shouldBe "First error"
            allErrors[0].suggestion shouldBe null

            allErrors[1].message shouldBe "Second error"
            allErrors[1].suggestion shouldBe "Fix it"

            val formatted = errors.formatAll()
            formatted shouldBe "Error at test.nox:10:5: First error\n" +
                "Error at test.nox:20:8: Second error\n" +
                "  Suggestion: Fix it\n"
        }

        test("CompilerError data class methods") {
            val loc = SourceLocation("file.nox", 1, 1)
            val err1 = CompilerError(loc, "msg", "sugg")
            val err2 = err1.copy()
            val err3 = err1.copy(message = "other")

            (err1 == err2) shouldBe true
            (err1 == err3) shouldBe false
            err1.hashCode() shouldBe err2.hashCode()
            err1.toString() shouldNotBe ""
        }
    })
