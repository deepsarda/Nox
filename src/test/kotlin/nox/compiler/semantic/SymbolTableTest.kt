package nox.compiler.semantic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import nox.compiler.ast.TypeRef

/**
 * Tests for [SymbolTable] scope-chain mechanics.
 */
class SymbolTableTest :
    FunSpec({

        test("define and lookup a symbol in global scope") {
            val table = SymbolTable()
            val sym = VarSymbol("x", TypeRef.INT, scopeDepth = 0)
            table.define("x", sym) shouldBe true
            table.lookup("x") shouldBe sym
        }

        test("lookup returns null for undefined name") {
            val table = SymbolTable()
            table.lookup("undefined").shouldBeNull()
        }

        test("lookup walks up the scope chain") {
            val global = SymbolTable()
            val globalSym = VarSymbol("x", TypeRef.INT, scopeDepth = 0)
            global.define("x", globalSym)

            val child = global.child()
            child.lookup("x") shouldBe globalSym
        }

        test("lookupLocal only checks current scope") {
            val global = SymbolTable()
            val globalSym = VarSymbol("x", TypeRef.INT, scopeDepth = 0)
            global.define("x", globalSym)

            val child = global.child()

            // x is in parent, not in the child's local scope
            child.lookupLocal("x").shouldBeNull()

            // but lookup finds it via chain
            child.lookup("x") shouldBe globalSym
        }

        test("duplicate definition in same scope returns false") {
            val table = SymbolTable()
            val sym1 = VarSymbol("x", TypeRef.INT, scopeDepth = 0)
            val sym2 = VarSymbol("x", TypeRef.STRING, scopeDepth = 0)

            table.define("x", sym1) shouldBe true
            table.define("x", sym2) shouldBe false

            // original symbol is preserved
            table.lookup("x") shouldBe sym1
        }

        test("same name in different scopes works (shadowing)") {
            val global = SymbolTable()
            val globalSym = VarSymbol("x", TypeRef.INT, scopeDepth = 0)
            global.define("x", globalSym)

            val child = global.child()
            val childSym = VarSymbol("x", TypeRef.STRING, scopeDepth = 1)
            child.define("x", childSym) shouldBe true

            // child sees its own shadowed version
            child.lookup("x") shouldBe childSym
            // parent still sees original
            global.lookup("x") shouldBe globalSym
        }

        test("child creates correct depth") {
            val global = SymbolTable()
            global.depth shouldBe 0

            val child1 = global.child()
            child1.depth shouldBe 1

            val child2 = child1.child()
            child2.depth shouldBe 2
        }

        test("allSymbols returns only current scope symbols") {
            val global = SymbolTable()
            global.define("a", VarSymbol("a", TypeRef.INT, scopeDepth = 0))
            global.define("b", VarSymbol("b", TypeRef.STRING, scopeDepth = 0))

            val child = global.child()
            child.define("c", VarSymbol("c", TypeRef.BOOLEAN, scopeDepth = 1))

            global.allSymbols() shouldHaveSize 2
            child.allSymbols() shouldHaveSize 1
        }

        test("deeply nested scope chain resolves correctly") {
            val global = SymbolTable()
            global.define("global", VarSymbol("global", TypeRef.INT, scopeDepth = 0))

            val scope1 = global.child()
            scope1.define("level1", VarSymbol("level1", TypeRef.STRING, scopeDepth = 1))

            val scope2 = scope1.child()
            scope2.define("level2", VarSymbol("level2", TypeRef.BOOLEAN, scopeDepth = 2))

            // scope2 can see all three
            scope2.lookup("global").shouldBeInstanceOf<VarSymbol>()
            scope2.lookup("level1").shouldBeInstanceOf<VarSymbol>()
            scope2.lookup("level2").shouldBeInstanceOf<VarSymbol>()

            // scope1 cannot see level2
            scope1.lookup("level2").shouldBeNull()
        }

        test("multiple symbols in different scopes are independent") {
            val global = SymbolTable()
            val childA = global.child()
            val childB = global.child()

            childA.define("x", VarSymbol("x", TypeRef.INT, scopeDepth = 1))
            childB.define("y", VarSymbol("y", TypeRef.STRING, scopeDepth = 1))

            // siblings cannot see each other's symbols
            childA.lookup("y").shouldBeNull()
            childB.lookup("x").shouldBeNull()
        }
    })
