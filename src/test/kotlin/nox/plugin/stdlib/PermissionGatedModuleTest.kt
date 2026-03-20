package nox.plugin.stdlib

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import nox.runtime.RuntimeContext
import java.nio.file.Files

class PermissionGatedModuleTest :
    FunSpec({

        // Helper contexts

        fun grantAllContext(): RuntimeContext = object : RuntimeContext {
            override fun yield(data: String) {}
            override fun returnResult(data: String) {}
            override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                PermissionResponse.Granted.Unconstrained
        }

        fun denyAllContext(reason: String = "denied by policy"): RuntimeContext = object : RuntimeContext {
            override fun yield(data: String) {}
            override fun returnResult(data: String) {}
            override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                PermissionResponse.Denied(reason)
        }

        /** Tracks which permission types were requested. */
        class TrackingContext : RuntimeContext {
            val requests = mutableListOf<PermissionRequest>()
            override fun yield(data: String) {}
            override fun returnResult(data: String) {}
            override suspend fun requestPermission(request: PermissionRequest): PermissionResponse {
                requests.add(request)
                return PermissionResponse.Granted.Unconstrained
            }
        }

        /** Selectively grants/denies based on a predicate. */
        fun selectiveContext(allow: (PermissionRequest) -> Boolean): RuntimeContext = object : RuntimeContext {
            override fun yield(data: String) {}
            override fun returnResult(data: String) {}
            override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                if (allow(request)) PermissionResponse.Granted.Unconstrained
                else PermissionResponse.Denied("selective deny")
        }

        // FileModule: permission checks

        test("File.read with granted permission reads file") {
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            Files.writeString(tmpFile, "hello nox")
            try {
                val result = runBlocking { FileModule.read(grantAllContext(), tmpFile.toString()) }
                result shouldBe "hello nox"
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("File.read with denied permission throws SecurityException") {
            val ex = shouldThrow<SecurityException> {
                runBlocking { FileModule.read(denyAllContext(), "/some/path.txt") }
            }
            ex.message shouldContain "Permission denied"
        }

        test("File.write with granted permission writes file") {
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            try {
                runBlocking { FileModule.write(grantAllContext(), tmpFile.toString(), "written by nox") }
                Files.readString(tmpFile) shouldBe "written by nox"
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("File.write with denied permission throws SecurityException") {
            shouldThrow<SecurityException> {
                runBlocking { FileModule.write(denyAllContext(), "/some/path.txt", "data") }
            }
        }

        test("File.exists with granted permission checks file existence") {
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            try {
                runBlocking { FileModule.exists(grantAllContext(), tmpFile.toString()) } shouldBe true
                runBlocking { FileModule.exists(grantAllContext(), "/nonexistent/file.txt") } shouldBe false
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("File.exists with denied permission throws SecurityException") {
            shouldThrow<SecurityException> {
                runBlocking { FileModule.exists(denyAllContext(), "/any/path") }
            }
        }

        test("File.delete with denied permission throws SecurityException") {
            shouldThrow<SecurityException> {
                runBlocking { FileModule.delete(denyAllContext(), "/some/file") }
            }
        }

        test("File operations request the correct permission types") {
            val ctx = TrackingContext()
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            try {
                runBlocking {
                    FileModule.read(ctx, tmpFile.toString())
                    FileModule.write(ctx, tmpFile.toString(), "data")
                    FileModule.exists(ctx, tmpFile.toString())
                }
                ctx.requests.size shouldBe 3
                ctx.requests[0]::class shouldBe PermissionRequest.File.Read::class
                ctx.requests[1]::class shouldBe PermissionRequest.File.Write::class
                ctx.requests[2]::class shouldBe PermissionRequest.File.Metadata::class
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("File.read permission carries the requested path") {
            val ctx = TrackingContext()
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            Files.writeString(tmpFile, "test")
            try {
                runBlocking { FileModule.read(ctx, tmpFile.toString()) }
                val req = ctx.requests[0] as PermissionRequest.File.Read
                req.path shouldBe tmpFile.toString()
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        // EnvModule: permission checks

        test("Env.get with granted permission returns env variable") {
            val result = runBlocking { EnvModule.get(grantAllContext(), "PATH") }
            result.isNotEmpty() shouldBe true
        }

        test("Env.get with denied permission throws SecurityException") {
            shouldThrow<SecurityException> {
                runBlocking { EnvModule.get(denyAllContext(), "SECRET_KEY") }
            }
        }

        test("Env.system with granted permission returns system property") {
            val result = runBlocking { EnvModule.system(grantAllContext(), "os.name") }
            result.isNotEmpty() shouldBe true
        }

        test("Env.system with denied permission throws SecurityException") {
            shouldThrow<SecurityException> {
                runBlocking { EnvModule.system(denyAllContext(), "os.name") }
            }
        }

        test("Env operations request the correct permission types") {
            val ctx = TrackingContext()
            runBlocking {
                EnvModule.get(ctx, "HOME")
                EnvModule.system(ctx, "user.dir")
            }
            ctx.requests.size shouldBe 2
            ctx.requests[0]::class shouldBe PermissionRequest.Env.ReadVar::class
            ctx.requests[1]::class shouldBe PermissionRequest.Env.SystemInfo::class
        }

        test("Env.get returns empty string for non-existent variable") {
            val result = runBlocking { EnvModule.get(grantAllContext(), "NOX_NONEXISTENT_VAR_12345") }
            result shouldBe ""
        }

        // Selective permission: grant reads but deny writes

        test("selective context allows reads but denies writes") {
            val ctx = selectiveContext { request ->
                request is PermissionRequest.File.Read || request is PermissionRequest.File.Metadata
            }
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            Files.writeString(tmpFile, "readable")
            try {
                // Read should succeed
                runBlocking { FileModule.read(ctx, tmpFile.toString()) } shouldBe "readable"

                // Write should fail
                shouldThrow<SecurityException> {
                    runBlocking { FileModule.write(ctx, tmpFile.toString(), "blocked") }
                }

                // File content should be unchanged
                Files.readString(tmpFile) shouldBe "readable"
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("selective context allows env reads but denies system info") {
            val ctx = selectiveContext { it is PermissionRequest.Env.ReadVar }
            runBlocking { EnvModule.get(ctx, "PATH") }
            shouldThrow<SecurityException> {
                runBlocking { EnvModule.system(ctx, "os.name") }
            }
        }

        // Denied response includes reason

        test("SecurityException includes deny reason from context") {
            val ctx = denyAllContext("sandbox policy: no file access for untrusted scripts")
            val ex = shouldThrow<SecurityException> {
                runBlocking { FileModule.read(ctx, "/etc/passwd") }
            }
            ex.message shouldContain "sandbox policy: no file access for untrusted scripts"
        }
    })
