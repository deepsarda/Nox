package nox.compiler.codegen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import nox.compiler.NoxCompiler
import nox.compiler.types.FieldSpec
import nox.compiler.types.TypeDescriptor
import java.nio.file.Path

/**
 * Each test compiles a short Nox snippet and inspects the resulting
 * [CompiledProgram] bytecode or [nox.compiler.NoxCompiler.CompilationResult.disassembly]
 * output. Opcodes are decoded via [Instruction] utilities.
 */
class CodeGeneratorTest :
    FunSpec({

        // Helpers

        /** Compile source and assert no errors, returning the CompilationResult. */
        fun compileOk(source: String): NoxCompiler.CompilationResult {
            val result = NoxCompiler.compile(source.trimIndent(), "test.nox")
            if (result.errors.hasErrors()) {
                println("Compilation failed for source:\n$source")
                result.errors.all().forEach { println("  ERROR: ${it.message} at ${it.loc}") }
            }
            result.errors.hasErrors() shouldBe false
            result.compiledProgram shouldNotBe null
            return result
        }

        /**
         * Compile [mainSource] as "main.nox" and supply [imports] as a map of
         * "filename.nox" to source string that the file-reader resolves.
         *
         * [basePath] should be an absolute path whose *parent* holds all imports.
         */
        fun compileWithImports(
            mainSource: String,
            imports: Map<String, String>,
            basePath: Path = Path.of("/fake/main.nox"),
        ): NoxCompiler.CompilationResult {
            val result =
                NoxCompiler.compile(
                    source = mainSource.trimIndent(),
                    fileName = "main.nox",
                    basePath = basePath,
                    fileReader = { path ->
                        val name = path.fileName.toString()
                        imports[name] ?: error("Unexpected import: $path")
                    },
                )
            result.errors.hasErrors() shouldBe false
            result.compiledProgram shouldNotBe null
            return result
        }

        /** Return all opcode values from the bytecode of [result]. */
        fun opcodes(result: NoxCompiler.CompilationResult): List<Int> =
            result.compiledProgram!!.bytecode.map { Instruction.opcode(it) }

        /** True if any instruction in [result]'s bytecode has the given opcode. */
        fun NoxCompiler.CompilationResult.hasOpcode(opcode: Int): Boolean =
            compiledProgram!!.bytecode.any { Instruction.opcode(it) == opcode }

        /** Disassembly string, non-null because compileOk asserts compiledProgram != null. */
        fun noxc(result: NoxCompiler.CompilationResult): String = result.disassembly ?: ""

        // Opcode selection

        test("intAddEmitsIADD") {
            val result =
                compileOk(
                    """
            int add(int a, int b) { return a + b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.IADD) shouldBe true
        }

        test("doubleAddEmitsDADD") {
            val result =
                compileOk(
                    """
            double add(double a, double b) { return a + b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.DADD) shouldBe true
        }

        test("intDoubleAddEmitsWidening") {
            // int + double should produce I2D widening conversion then DADD
            val result =
                compileOk(
                    """
            double widen(int a, double b) { return a + b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.I2D) shouldBe true
            result.hasOpcode(Opcode.DADD) shouldBe true
        }

        test("incrementEmitsIINC") {
            val result =
                compileOk(
                    """
            main() {
                int i = 0;
                i++;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.IINC) shouldBe true
        }

        test("decrementEmitsIDEC") {
            val result =
                compileOk(
                    """
            main() {
                int i = 10;
                i--;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.IDEC) shouldBe true
        }

        test("compoundAddEmitsIINCN") {
            val result =
                compileOk(
                    """
            main() {
                int x = 0;
                int y = 5;
                x += y;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.IINCN) shouldBe true
        }

        test("bitwiseAndEmitsBAND") {
            val result =
                compileOk(
                    """
            int mask(int a, int b) { return a & b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.BAND) shouldBe true
        }

        test("stringEqualsEmitsSEQ") {
            val result =
                compileOk(
                    """
            main() {
                string s = "hello";
                boolean b = s == "hello";
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.SEQ) shouldBe true
        }

        test("stringNotEqualsEmitsSNE") {
            val result =
                compileOk(
                    """
            main() {
                string s = "hello";
                boolean b = s != "world";
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.SNE) shouldBe true
        }

        test("intSubEmitsISUB") {
            val result =
                compileOk(
                    """
            int sub(int a, int b) { return a - b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.ISUB) shouldBe true
        }

        test("intMulEmitsIMUL") {
            val result =
                compileOk(
                    """
            int mul(int a, int b) { return a * b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.IMUL) shouldBe true
        }

        test("intDivEmitsIDIV") {
            val result =
                compileOk(
                    """
            int div(int a, int b) { return a / b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.IDIV) shouldBe true
        }

        test("intModEmitsIMOD") {
            val result =
                compileOk(
                    """
            int mod(int a, int b) { return a % b; }
            main() { return "ok"; }
        """,
                )
            result.hasOpcode(Opcode.IMOD) shouldBe true
        }

        test("logicalOrEmitsOR") {
            val result =
                compileOk(
                    """
            boolean id(boolean v) { return v; }
            main() {
                boolean b = id(true) || id(false);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.OR) shouldBe true
        }

        test("shiftLeftEmitsSHL") {
            val result =
                compileOk(
                    """
            int id(int v) { return v; }
            main() {
                int x = id(1) << id(4);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.SHL) shouldBe true
        }

        test("bitwiseOrEmitsBOR") {
            val result =
                compileOk(
                    """
            int id(int v) { return v; }
            main() {
                int x = id(3) | id(12);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.BOR) shouldBe true
        }

        test("bitwiseXorEmitsBXOR") {
            val result =
                compileOk(
                    """
            int id(int v) { return v; }
            main() {
                int x = id(5) ^ id(3);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.BXOR) shouldBe true
        }

        // Super-instructions

        test("fieldAccessEmitsHACC") {
            val result =
                compileOk(
                    """
            type Point { int x; int y; }
            main() {
                Point p = { x: 1, y: 2 };
                int v = p.x;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.HACC) shouldBe true
        }

        test("fieldMutationEmitsHMOD") {
            val result =
                compileOk(
                    """
            type Point { int x; int y; }
            main() {
                Point p = { x: 1, y: 2 };
                p.x = 10;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.HMOD) shouldBe true
        }

        test("methodCallEmitsSCALL") {
            val result =
                compileOk(
                    """
            main() {
                string s = "hello";
                string u = s.upper();
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.SCALL) shouldBe true
        }

        test("deepPathEmitsAGET_PATH") {
            // json.a.b where all intermediate types are json triggers AGET_PATH path collapsing.
            // Using `json sub = config.server.db` keeps both intermediate values as json.
            val result =
                compileOk(
                    """
            main() {
                json config = null;
                json sub = config.server.db;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.AGET_PATH) shouldBe true
        }

        // Control flow

        test("ifEmitsJIF") {
            val result =
                compileOk(
                    """
            main() {
                boolean b = true;
                if (b) {
                    int x = 1;
                }
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.JIF) shouldBe true
        }

        test("ifElseEmitsJIFAndJMP") {
            val result =
                compileOk(
                    """
            main() {
                boolean b = true;
                if (b) {
                    int x = 1;
                } else {
                    int x = 2;
                }
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.JIF) shouldBe true
            result.hasOpcode(Opcode.JMP) shouldBe true
        }

        test("whileEmitsBackwardJMP") {
            val result =
                compileOk(
                    """
            main() {
                int i = 0;
                while (i < 10) {
                    i++;
                }
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.JMP) shouldBe true
            result.hasOpcode(Opcode.JIF) shouldBe true
        }

        test("forLoopEmitsCorrectStructure") {
            val result =
                compileOk(
                    """
            main() {
                for (int i = 0; i < 10; i++) {
                }
                return "ok";
            }
        """,
                )
            // for loop needs: LDI (init), JIF (check), JMP (back-edge), IINC (update)
            result.hasOpcode(Opcode.JIF) shouldBe true
            result.hasOpcode(Opcode.JMP) shouldBe true
            result.hasOpcode(Opcode.IINC) shouldBe true
        }

        test("foreachDesugarsToIndexLoop") {
            val result =
                compileOk(
                    """
            main() {
                int[] arr = [1, 2, 3];
                foreach (int x in arr) {
                }
                return "ok";
            }
        """,
                )
            // foreach needs SCALL/__arr_length (for length), AGET_IDX (element access), IINC (counter)
            result.hasOpcode(Opcode.SCALL) shouldBe true
            result.hasOpcode(Opcode.AGET_IDX) shouldBe true
            result.hasOpcode(Opcode.IINC) shouldBe true
        }

        // Memory management

        test("killRefEmittedAtScopeExit") {
            val result =
                compileOk(
                    """
            boolean id(boolean v) { return v; }
            main() {
                if (id(true)) {
                    string s = "hello";
                }
                return "ok";
            }
        """,
                )
            // The string `s` is a reference type, so KILL_REF should be emitted when the if-block exits
            result.hasOpcode(Opcode.KILL_REF) shouldBe true
        }

        test("registerReusedAfterDeath") {
            // After `s` is KILL_REF'd, the next string should reuse the same register.
            // We verify indirectly: the total rMem frame size should be small (≤ 2).
            val result =
                compileOk(
                    """
            main() {
                int i = 0;
                while (i < 3) {
                    string s = "loop";
                    i++;
                }
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            val mainMeta = cp.functions[cp.mainFuncIndex]
            // A simple loop with one string variable should not need more than 2 rMem registers
            (mainMeta.referenceFrameSize <= 2) shouldBe true
        }

        // Constants

        test("smallIntUsesLDI") {
            // Values 0-65535 should use LDI (immediate), not LDC (pool)
            val result =
                compileOk(
                    """
            main() {
                int x = 42;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.LDI) shouldBe true
        }

        test("largeIntUsesLDC") {
            // Values > 0xFFFF must go through the constant pool
            val result =
                compileOk(
                    """
            main() {
                int x = 100000;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.LDC) shouldBe true
        }

        test("stringUsesPool") {
            val result =
                compileOk(
                    """
            main() {
                string s = "hello world";
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            cp.constantPool.any { it == "hello world" } shouldBe true
            result.hasOpcode(Opcode.LDC) shouldBe true
        }

        test("constantPoolDeduplication") {
            // The same string "hello" appears twice, should be one constant pool entry
            val result =
                compileOk(
                    """
            main() {
                string a = "hello";
                string b = "hello";
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            val helloCount = cp.constantPool.count { it == "hello" }
            helloCount shouldBe 1
        }

        test("doubleArrayIntElementsWidened") {
            // double[] arr = [20.1, 10, 32.34]
            // the literal `10` is an int and must be
            // widened with I2D before being pushed into the double array.
            val result =
                compileOk(
                    """
            main() {
                double[] arr = [20.1, 10, 32.34];
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.I2D) shouldBe true
        }

        // Exception table

        test("tryCatchGeneratesEntry") {
            val result =
                compileOk(
                    """
            main() {
                try {
                    int x = 1;
                } catch (err) {
                    string msg = err;
                }
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            cp.exceptionTable.size shouldBeGreaterThan 0
        }

        test("nestedTryCatchOrdering") {
            // Inner try-catch must appear before outer one in the exception table
            val result =
                compileOk(
                    """
            main() {
                try {
                    try {
                        int x = 1;
                    } catch (inner) {
                        string m = inner;
                    }
                } catch (outer) {
                    string m = outer;
                }
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            cp.exceptionTable.size shouldBe 2
            // Inner entry should be first (CodeGenerator emits inner before outer):
            // inner's protected region must be strictly inside the outer's
            val first = cp.exceptionTable[0]
            val second = cp.exceptionTable[1]
            // Inner starts at or after outer's start, and ends before outer's end
            (first.startPC >= second.startPC) shouldBe true
            (first.endPC <= second.endPC) shouldBe true
        }

        test("catchAllUsesNullType") {
            // `catch (err) { }` without a type should produce exceptionType = null
            val result =
                compileOk(
                    """
            main() {
                try {
                    int x = 1;
                } catch (err) { }
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            cp.exceptionTable.any { it.exceptionType == null } shouldBe true
        }

        // Disassembly

        test("noxcHasSourceAnnotations") {
            val result =
                compileOk(
                    """
            main() {
                int x = 42;
                return "ok";
            }
        """,
                )
            // Source line annotations use the format "  ; line N  sourcetext"
            val disasm = noxc(result)
            disasm shouldContain "; line "
        }

        test("noxcHasLabels") {
            val result =
                compileOk(
                    """
            main() {
                int i = 0;
                while (i < 10) {
                    i++;
                }
                return "ok";
            }
        """,
                )
            val disasm = noxc(result)
            // While loops emit loop_start and loop_exit labels
            disasm shouldContain "loop_start"
            disasm shouldContain "loop_exit"
        }

        test("noxcHasRegisterPrefixes") {
            val result =
                compileOk(
                    """
            void add(int a, int b) { int c = a + b; }
            main() { return "ok"; }
        """,
                )
            val disasm = noxc(result)
            // Primitive registers should appear as p0, p1, etc.
            disasm shouldContain "p0"
        }

        test("noxcHasConstantPool") {
            val result =
                compileOk(
                    """
            main() {
                string s = "hello";
                return "ok";
            }
        """,
                )
            val disasm = noxc(result)
            disasm shouldContain ".constants"
        }

        test("noxcHasExceptionTable") {
            val result =
                compileOk(
                    """
            main() {
                try {
                    int x = 1;
                } catch (err) {
                }
                return "ok";
            }
        """,
                )
            val disasm = noxc(result)
            disasm shouldContain ".exceptions"
        }

        test("noxcHasSummary") {
            val result =
                compileOk(
                    """
            main() { return "ok"; }
        """,
                )
            val disasm = noxc(result)
            // The disassembly summary section is labelled .summary
            disasm shouldContain ".summary"
        }

        // FuncMeta presence

        test("importFuncGetsFuncMeta") {
            // Even without real imports, user-defined functions should appear in functions[]
            val result =
                compileOk(
                    """
            int add(int a, int b) { return a + b; }
            int sub(int a, int b) { return a - b; }
            main() { return "ok"; }
        """,
                )
            val cp = result.compiledProgram!!
            val funcNames = cp.functions.map { it.name }
            funcNames shouldContain "add"
            funcNames shouldContain "sub"
            funcNames shouldContain "main"
        }

        test("mainFuncIndexPointsToMain") {
            val result =
                compileOk(
                    """
            int helper(int x) { return x * 2; }
            main() { return "ok"; }
        """,
                )
            val cp = result.compiledProgram!!
            cp.mainFuncIndex shouldNotBe -1
            cp.functions[cp.mainFuncIndex].name shouldBe "main"
        }

        test("paramCountInFuncMeta") {
            val result =
                compileOk(
                    """
            int add(int a, int b, int c) { return a + b + c; }
            main() { return "ok"; }
        """,
                )
            val cp = result.compiledProgram!!
            val addMeta = cp.functions.first { it.name == "add" }
            addMeta.paramCount shouldBe 3
        }

        test("globalSlotsAssigned") {
            val result =
                compileOk(
                    """
            int counter = 0;
            string label = "test";
            main() { return "ok"; }
        """,
                )
            val cp = result.compiledProgram!!
            // One prim global (counter) + one ref global (label)
            cp.totalGlobalPrimitiveSlots shouldBe 1
            cp.totalGlobalReferenceSlots shouldBe 1
        }

        test("globalInitUsesGlobalFlag") {
            val result =
                compileOk(
                    """
            int counter = 42;
            main() { return "ok"; }
        """,
                )
            // Global init now writes directly via [G] flag on LDI dest operand
            result.hasOpcode(Opcode.LDI) shouldBe true
        }

        // Unary operators

        test("negateDoubleEmitsDNEG") {
            val result =
                compileOk(
                    """
            double id(double v) { return v; }
            main() {
                double x = -id(3.14);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.DNEG) shouldBe true
        }

        test("notEmitsNOT") {
            val result =
                compileOk(
                    """
            boolean id(boolean v) { return v; }
            main() {
                boolean b = !id(true);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.NOT) shouldBe true
        }

        test("bitwiseNotEmitsBNOT") {
            val result =
                compileOk(
                    """
            int id(int v) { return v; }
            main() {
                int x = ~id(0);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.BNOT) shouldBe true
        }

        // Template literal conversions

        test("templateLiteralIntEmitsI2S") {
            val dollar = '$'
            val result =
                compileOk(
                    """
            main() {
                int n = 42;
                string s = `value=$dollar{n}`;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.I2S) shouldBe true
        }

        test("templateLiteralDoubleEmitsD2S") {
            val dollar = '$'
            val result =
                compileOk(
                    """
            main() {
                double d = 3.14;
                string s = `val=$dollar{d}`;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.D2S) shouldBe true
        }

        test("templateLiteralBoolEmitsB2S") {
            val dollar = '$'
            val result =
                compileOk(
                    """
            main() {
                boolean flag = true;
                string s = `flag=$dollar{flag}`;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.B2S) shouldBe true
        }

        // Cast

        test("castJsonToStructEmitsCAST_STRUCT") {
            val result =
                compileOk(
                    """
            type Config { string name; }
            main() {
                json j = null;
                Config c = j as Config;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.CAST_STRUCT) shouldBe true
        }

        test("castDescriptorInPool") {
            val result =
                compileOk(
                    """
            type Config {
                string name;
                int count;
            }
            main() {
                json j = null;
                Config c = j as Config;
                return "ok";
            }
        """,
                )
            val pool = result.compiledProgram!!.constantPool
            val descriptor = pool.filterIsInstance<TypeDescriptor>().first()
            descriptor.name shouldBe "Config"
            descriptor.fields["name"] shouldBe FieldSpec.STRING
            descriptor.fields["count"] shouldBe FieldSpec.INT
        }

        test("castNestedStructHasRecursiveDescriptor") {
            val result =
                compileOk(
                    """
            type Address { string city; }
            type User {
                string name;
                Address addr;
            }
            main() {
                json j = null;
                User u = j as User;
                return "ok";
            }
        """,
                )
            val pool = result.compiledProgram!!.constantPool
            val userDesc = pool.filterIsInstance<TypeDescriptor>().first { it.name == "User" }
            val addrDesc = pool.filterIsInstance<TypeDescriptor>().first { it.name == "Address" }

            val addrField = userDesc.fields["addr"] as FieldSpec.Struct
            val linkedDesc = pool[addrField.descriptorIdx] as TypeDescriptor
            linkedDesc.name shouldBe "Address"
        }

        test("castRecursiveStructDoesNotLoop") {
            val result =
                compileOk(
                    """
            type TreeNode {
                string value;
                TreeNode left;
            }
            main() {
                json j = null;
                TreeNode t = j as TreeNode;
                return "ok";
            }
        """,
                )
            val pool = result.compiledProgram!!.constantPool
            val treeDesc = pool.filterIsInstance<TypeDescriptor>().first()

            val leftField = treeDesc.fields["left"] as FieldSpec.Struct
            val linkedDesc = pool[leftField.descriptorIdx] as TypeDescriptor
            linkedDesc shouldBe treeDesc
        }

        test("castStructArrayUsesSubOp1") {
            val result =
                compileOk(
                    """
            type Item { string name; }
            main() {
                json j = null;
                Item[] items = j as Item[];
                return "ok";
            }
        """,
                )
            // SubOp 1 should be used for array casting
            val hasArrayCast =
                result.compiledProgram!!.bytecode.any { inst ->
                    Instruction.opcode(inst) == Opcode.CAST_STRUCT && Instruction.subOp(inst) == 1
                }
            hasArrayCast shouldBe true
        }

        test("castNoxcShowsTypeName") {
            val result =
                compileOk(
                    """
            type Config { string name; }
            main() {
                json j = null;
                Config c = j as Config;
                return "ok";
            }
        """,
                )
            result.disassembly.shouldContain("as Config")
        }

        test("castNoxcPoolShowsTypeDescriptor") {
            val result =
                compileOk(
                    """
            type Config { string name; }
            main() {
                json j = null;
                Config c = j as Config;
                return "ok";
            }
        """,
                )
            result.disassembly.shouldContain("type  Config { name: string }")
        }

        // Global variable load

        test("globalPrimVarLoadEmitsMOVWithGlobalFlag") {
            val result =
                compileOk(
                    """
            int counter = 5;
            main() {
                int x = counter;
                return "ok";
            }
        """,
                )
            // Global loads now use MOV with [G] flag (bit 15) on the source operand
            result.hasOpcode(Opcode.MOV) shouldBe true
        }

        test("globalRefVarLoadEmitsMOVRWithGlobalFlag") {
            val result =
                compileOk(
                    """
            string label = "hi";
            main() {
                string s = label;
                return "ok";
            }
        """,
                )
            // Global loads now use MOVR with [G] flag (bit 15) on the source operand
            result.hasOpcode(Opcode.MOVR) shouldBe true
        }

        // .length property

        test("stringLengthEmitsSCALL") {
            val result =
                compileOk(
                    """
            main() {
                string s = "hello";
                int n = s.length();
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.SCALL) shouldBe true
        }

        test("arrayLengthEmitsSCALL") {
            val result =
                compileOk(
                    """
            main() {
                int[] arr = [1, 2, 3];
                int n = arr.length();
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.SCALL) shouldBe true
        }

        // Index access

        test("indexAccessEmitsAGET_IDX") {
            val result =
                compileOk(
                    """
            main() {
                int[] arr = [10, 20, 30];
                int x = arr[1];
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.AGET_IDX) shouldBe true
        }

        // Assignment forms

        test("simpleAssignToGlobalUsesGlobalFlag") {
            val result =
                compileOk(
                    """
            int counter = 0;
            main() {
                counter = 42;
                return "ok";
            }
        """,
                )
            // Global assignment now writes directly via [G] flag on LDI dest operand
            result.hasOpcode(Opcode.LDI) shouldBe true
        }

        test("indexAssignEmitsASET_IDX") {
            val result =
                compileOk(
                    """
            main() {
                int[] arr = [1, 2, 3];
                arr[0] = 99;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.ASET_IDX) shouldBe true
        }

        test("compoundSubAssignEmitsIDECN") {
            val result =
                compileOk(
                    """
            main() {
                int x = 10;
                int y = 3;
                x -= y;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.IDECN) shouldBe true
        }

        test("compoundMulAssignEmitsIMUL") {
            val result =
                compileOk(
                    """
            main() {
                int x = 4;
                int y = 3;
                x *= y;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.IMUL) shouldBe true
        }

        test("doubleAddAssignEmitsDINCN") {
            val result =
                compileOk(
                    """
            main() {
                double x = 1.0;
                double y = 2.0;
                x += y;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.DINCN) shouldBe true
        }

        test("doubleSubAssignEmitsDDECN") {
            val result =
                compileOk(
                    """
            main() {
                double x = 1.0;
                double y = 2.0;
                x -= y;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.DDECN) shouldBe true
        }

        // Double increment/decrement

        test("doubleIncrementEmitsDINC") {
            val result =
                compileOk(
                    """
            main() {
                double d = 1.0;
                d++;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.DINC) shouldBe true
        }

        test("doubleDecrementEmitsDDEC") {
            val result =
                compileOk(
                    """
            main() {
                double d = 5.0;
                d--;
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.DDEC) shouldBe true
        }

        // Struct literal

        test("structLiteralEmitsNEW_OBJ") {
            val result =
                compileOk(
                    """
            type Point {
                int x;
                int y;
            }
            main() {
                Point p = { x: 1, y: 2 };
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.NEW_OBJ) shouldBe true
        }

        test("structLiteralEmitsOBJ_SET") {
            val result =
                compileOk(
                    """
            type Point {
                int x;
                int y;
            }
            main() {
                Point p = { x: 1, y: 2 };
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.OBJ_SET) shouldBe true
        }

        test("arrayPushEmitsARR_PUSH") {
            val result =
                compileOk(
                    """
            main() {
                int[] arr = [1, 2];
                arr.push(3);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.ARR_PUSH) shouldBe true
        }

        // UFCS call

        test("ufcsCallEmitsCALL") {
            val result =
                compileOk(
                    """
            type Point {
                int x;
                int y;
            }
            int getX(Point p) {
                return p.x;
            }
            main() {
                Point p = { x: 5, y: 3 };
                int x = p.getX();
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.CALL) shouldBe true
        }

        // Builtin namespace SCALL

        test("builtinNamespaceEmitsSCALL") {
            val result =
                compileOk(
                    """
            main() {
                double x = Math.sqrt(4.0);
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.SCALL) shouldBe true
        }

        // Break / Continue

        test("breakExitsLoopEmitsJMP") {
            val result =
                compileOk(
                    """
            main() {
                while (true) {
                    break;
                }
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.JMP) shouldBe true
        }

        test("continueJumpsToUpdateEmitsJMP") {
            val result =
                compileOk(
                    """
            main() {
                for (int i = 0; i < 10; i++) {
                    if (i == 5) { continue; }
                }
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.JMP) shouldBe true
        }

        // Yield

        test("yieldEmitsYIELD") {
            val result =
                compileOk(
                    """
            main() {
                yield "hello";
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.YIELD) shouldBe true
        }

        // Throw

        test("throwEmitsTHROW") {
            val result =
                compileOk(
                    """
            main() {
                try {
                    throw "oops";
                } catch (err) { }
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.THROW) shouldBe true
        }

        // Typed catch

        test("typedCatchHasExceptionType") {
            val result =
                compileOk(
                    """
            main() {
                try {
                    int x = 1;
                } catch (TypeError e) {
                    string m = e;
                }
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            cp.exceptionTable.any { it.exceptionType == "TypeError" } shouldBe true
        }

        test("resourceGuardCatchEmitsKILL") {
            val result =
                compileOk(
                    """
            main() {
                try {
                    int x = 1;
                } catch (QuotaExceededError e) {
                    string m = e;
                }
                return "ok";
            }
        """,
                )
            result.hasOpcode(Opcode.KILL) shouldBe true
        }

        // Return forms

        test("voidReturnEmitsRET") {
            // void function with bare `return;` and should emit RET with a=0
            val result =
                compileOk(
                    """
            void doWork() { return; }
            main() { return "ok"; }
        """,
                )
            val cp = result.compiledProgram!!
            // Find the doWork function and check it has a RET with a=0
            val doWorkMeta = cp.functions.first { it.name == "doWork" }
            val retInstr =
                cp.bytecode
                    .drop(doWorkMeta.entryPC)
                    .take(doWorkMeta.sourceLines.size)
                    .firstOrNull { Instruction.opcode(it) == Opcode.RET }
            retInstr shouldNotBe null
            Instruction.opA(retInstr!!) shouldBe 1
        }

        test("intReturnEmitsRETWithA") {
            // non-void return should emit RET with a != 0 (the result register)
            val result =
                compileOk(
                    """
            int get42() { return 42; }
            main() { return "ok"; }
        """,
                )
            val cp = result.compiledProgram!!
            val fnMeta = cp.functions.first { it.name == "get42" }
            val retInstr =
                cp.bytecode
                    .drop(fnMeta.entryPC)
                    .take(fnMeta.sourceLines.size)
                    .firstOrNull { Instruction.opcode(it) == Opcode.RET }
            retInstr shouldNotBe null
            Instruction.opA(retInstr!!) shouldBe 0 // register p0 holds the result
        }

        test("variablesWithDisjointLifetimesReuseRegisters") {
            val result =
                compileOk(
                    """
            main() {
                int a = 10;
                int b = a + 5;
                int c = 124; // Reuses a
                b += c;
                return "ok";
            }
        """,
                )
            val cp = result.compiledProgram!!
            print(result.disassembly)
            val mainMeta = cp.functions[cp.mainFuncIndex]
            // Variables a, b, c. Without reuse, pMem = 3.
            // With reuse, 'c' reuses 'a's old pMem index, so max pMem frame size is 2!
            mainMeta.primitiveFrameSize shouldBe 2
        }

        // Module meta

        test("importedModuleAppearsInModuleMeta") {
            val helperSrc = """
            int twice(int x) { return x * 2; }
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() {
                int v = helper.twice(5);
                return "ok";
            }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            // Should have two modules: helper + main
            cp.modules.size shouldBe 2
            cp.modules.map { it.namespace } shouldContain "helper"
            cp.modules.map { it.namespace } shouldContain "main"
        }

        test("importedFunctionAppearsInFunctions") {
            val helperSrc = """
            int square(int x) { return x * x; }
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() {
                int v = helper.square(4);
                return "ok";
            }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            val funcNames = cp.functions.map { it.name }
            funcNames shouldContain "square"
            funcNames shouldContain "main"
        }

        test("callingImportedFunctionEmitsCALL") {
            val helperSrc = """
            int add(int a, int b) { return a + b; }
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() {
                int v = helper.add(3, 4);
                return "ok";
            }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            result.hasOpcode(Opcode.CALL) shouldBe true
        }

        test("importedModuleSourcePathInMeta") {
            val helperSrc = """
            int id(int x) { return x; }
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            val helperMeta = cp.modules.first { it.namespace == "helper" }
            // Source path should end with helper.nox
            helperMeta.sourcePath.endsWith("helper.nox") shouldBe true
        }

        // Exported functions & types

        test("importedModuleExportedFunctionsListIsNonEmpty") {
            val helperSrc = """
            int triple(int x) { return x * 3; }
            int halve(int x) { return x / 2; }
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            val helperMeta = cp.modules.first { it.namespace == "helper" }
            helperMeta.exportedFunctions.size shouldBe 2
        }

        test("importedTypeAppearsInExportedTypes") {
            // Type-only module then no function that returns the struct (avoids struct-return complexity)
            val helperSrc = """
            type Vec2 { int x; int y; }
            int sumVec(int x, int y) { return x + y; }
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            val helperMeta = cp.modules.first { it.namespace == "helper" }
            helperMeta.exportedTypes shouldContain "Vec2"
        }

        // Init block per module

        test("importedModuleGlobalProducesInitBlock") {
            // The helper has a global var with an initializer, should produce <module_init:helper>
            val helperSrc = """
            int BASE = 100;
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            val initNames = cp.functions.map { it.name }
            initNames.any { it.startsWith("<module_init:helper>") } shouldBe true
        }

        test("initFuncIndexInModuleMetaIsValid") {
            val helperSrc = """
            int BASE = 42;
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            val helperMeta = cp.modules.first { it.namespace == "helper" }
            helperMeta.initFuncIndex shouldNotBe -1
            // The function at that index must be the init block
            cp.functions[helperMeta.initFuncIndex].name shouldBe "<module_init:helper>"
        }

        // Cross-module global slots

        test("importedModuleGlobalSlotsAreAllocated") {
            val helperSrc = """
            int helperInt = 10;
            string helperStr = "hello";
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            // helper contributes 1 prim global + 1 ref global
            cp.totalGlobalPrimitiveSlots shouldBeGreaterThan 0
            cp.totalGlobalReferenceSlots shouldBeGreaterThan 0
        }

        test("importedGlobalCountInModuleMeta") {
            val helperSrc = """
            int a = 1;
            int b = 2;
        """
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val cp = result.compiledProgram!!
            val helperMeta = cp.modules.first { it.namespace == "helper" }
            helperMeta.globalPrimitiveCount shouldBe 2
        }

        // Multiple imports

        test("twoImportsProduceThreeModules") {
            val aSrc = "int fa(int x) { return x + 1; }"
            val bSrc = "int fb(int x) { return x + 2; }"
            val mainSrc = """
            import "a.nox" as a;
            import "b.nox" as b;

            int x1 = 10;
            int y1 = 20;

            main() {
                int x = a.fa(1) + x1;
                int y = b.fb(x) + y1;
                return "ok";
            }
        """
            val result = compileWithImports(mainSrc, mapOf("a.nox" to aSrc, "b.nox" to bSrc))
            // print(result.disassembly)
            val cp = result.compiledProgram!!
            cp.modules.size shouldBe 3 // a, b, main
            cp.modules.map { it.namespace } shouldContain "a"
            cp.modules.map { it.namespace } shouldContain "b"
        }

        test("disassemblyShowsModulesInHeader") {
            val helperSrc = "int id(int x) { return x; }"
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val disasm = result.disassembly ?: ""
            // Header should list Modules section
            disasm shouldContain "Modules:"
            disasm shouldContain "helper"
        }

        test("disassemblyShowsInitBlock") {
            val helperSrc = "int LIMIT = 50;"
            val mainSrc = """
            import "helper.nox" as helper;
            main() { return "ok"; }
        """
            val result = compileWithImports(mainSrc, mapOf("helper.nox" to helperSrc))
            val disasm = result.disassembly ?: ""
            disasm shouldContain ".init"
        }
    })
