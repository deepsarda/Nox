package nox.plugin.stdlib

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import nox.runtime.*
import java.nio.file.Files

class PermissionGatedModuleTest :
    FunSpec({

        // Helper contexts

        fun grantAllContext(): RuntimeContext =
            object : RuntimeContext {
                override fun yield(data: String) {}

                override fun returnResult(data: String) {}

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                    PermissionResponse.Granted.Unconstrained

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    ResourceResponse.Denied()
            }

        fun denyAllContext(reason: String = "denied by policy"): RuntimeContext =
            object : RuntimeContext {
                override fun yield(data: String) {}

                override fun returnResult(data: String) {}

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                    PermissionResponse.Denied(reason)

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    ResourceResponse.Denied()
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

            override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                ResourceResponse.Denied()
        }

        /** Selectively grants/denies based on a predicate. */
        fun selectiveContext(allow: (PermissionRequest) -> Boolean): RuntimeContext =
            object : RuntimeContext {
                override fun yield(data: String) {}

                override fun returnResult(data: String) {}

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                    if (allow(request)) {
                        PermissionResponse.Granted.Unconstrained
                    } else {
                        PermissionResponse.Denied("selective deny")
                    }

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    ResourceResponse.Denied()
            }

        /** Returns a FileGrant with the specified constraints for all requests. */
        fun fileGrantContext(grant: PermissionResponse.Granted.FileGrant): RuntimeContext =
            object : RuntimeContext {
                override fun yield(data: String) {}

                override fun returnResult(data: String) {}

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse = grant

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    ResourceResponse.Denied()
            }

        /** Returns an HttpGrant with the specified constraints for all requests. */
        fun httpGrantContext(grant: PermissionResponse.Granted.HttpGrant): RuntimeContext =
            object : RuntimeContext {
                override fun yield(data: String) {}

                override fun returnResult(data: String) {}

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse = grant

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    ResourceResponse.Denied()
            }

        /** Returns an EnvGrant with the specified constraints for all requests. */
        fun envGrantContext(grant: PermissionResponse.Granted.EnvGrant): RuntimeContext =
            object : RuntimeContext {
                override fun yield(data: String) {}

                override fun returnResult(data: String) {}

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse = grant

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    ResourceResponse.Denied()
            }

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
            val ex =
                shouldThrow<SecurityException> {
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

        test("FileGrant.readOnly blocks write") {
            val ctx = fileGrantContext(PermissionResponse.Granted.FileGrant(readOnly = true))
            shouldThrow<SecurityException> {
                runBlocking { FileModule.write(ctx, "/tmp/nox_test.txt", "data") }
            }.message shouldContain "readOnly"
        }

        test("FileGrant.readOnly blocks append") {
            val ctx = fileGrantContext(PermissionResponse.Granted.FileGrant(readOnly = true))
            shouldThrow<SecurityException> {
                runBlocking { FileModule.append(ctx, "/tmp/nox_test.txt", "data") }
            }.message shouldContain "readOnly"
        }

        test("FileGrant.readOnly blocks delete") {
            val ctx = fileGrantContext(PermissionResponse.Granted.FileGrant(readOnly = true))
            shouldThrow<SecurityException> {
                runBlocking { FileModule.delete(ctx, "/tmp/nox_test.txt") }
            }.message shouldContain "readOnly"
        }

        test("FileGrant.readOnly allows read") {
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            Files.writeString(tmpFile, "readable content")
            val ctx = fileGrantContext(PermissionResponse.Granted.FileGrant(readOnly = true))
            try {
                val result = runBlocking { FileModule.read(ctx, tmpFile.toString()) }
                result shouldBe "readable content"
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("FileGrant.maxBytes truncates read content") {
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            Files.writeString(tmpFile, "abcdefghij") // 10 bytes
            val ctx = fileGrantContext(PermissionResponse.Granted.FileGrant(maxBytes = 5))
            try {
                val result = runBlocking { FileModule.read(ctx, tmpFile.toString()) }
                result.toByteArray().size shouldBe 5
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("FileGrant.maxBytes blocks oversized write") {
            val ctx = fileGrantContext(PermissionResponse.Granted.FileGrant(maxBytes = 5))
            shouldThrow<SecurityException> {
                runBlocking { FileModule.write(ctx, "/tmp/nox_test.txt", "this is way too long") }
            }.message shouldContain "maxBytes"
        }

        test("FileGrant.maxBytes allows small enough write") {
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            val ctx = fileGrantContext(PermissionResponse.Granted.FileGrant(maxBytes = 100))
            try {
                runBlocking { FileModule.write(ctx, tmpFile.toString(), "ok") }
                Files.readString(tmpFile) shouldBe "ok"
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("FileGrant.allowedDirectories blocks paths outside whitelist") {
            val ctx =
                fileGrantContext(
                    PermissionResponse.Granted.FileGrant(allowedDirectories = listOf("/safe/dir")),
                )
            shouldThrow<SecurityException> {
                runBlocking { FileModule.read(ctx, "/etc/passwd") }
            }.message shouldContain "outside allowed directories"
        }

        test("FileGrant.allowedDirectories allows paths inside whitelist") {
            val tmpDir = Files.createTempDirectory("nox_test_safe_")
            val tmpFile = Files.createTempFile(tmpDir, "data_", ".txt")
            Files.writeString(tmpFile, "safe")
            val ctx =
                fileGrantContext(
                    PermissionResponse.Granted.FileGrant(allowedDirectories = listOf(tmpDir.toString())),
                )
            try {
                val result = runBlocking { FileModule.read(ctx, tmpFile.toString()) }
                result shouldBe "safe"
            } finally {
                Files.deleteIfExists(tmpFile)
                Files.deleteIfExists(tmpDir)
            }
        }

        test("FileGrant.allowedExtensions blocks disallowed extensions") {
            val tmpFile = Files.createTempFile("nox_test_", ".exe")
            Files.writeString(tmpFile, "bad")
            val ctx =
                fileGrantContext(
                    PermissionResponse.Granted.FileGrant(allowedExtensions = listOf("txt", "json")),
                )
            try {
                shouldThrow<SecurityException> {
                    runBlocking { FileModule.read(ctx, tmpFile.toString()) }
                }.message shouldContain "allowed extensions"
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("FileGrant.allowedExtensions allows permitted extensions") {
            val tmpFile = Files.createTempFile("nox_test_", ".txt")
            Files.writeString(tmpFile, "good")
            val ctx =
                fileGrantContext(
                    PermissionResponse.Granted.FileGrant(allowedExtensions = listOf("txt", "json")),
                )
            try {
                val result = runBlocking { FileModule.read(ctx, tmpFile.toString()) }
                result shouldBe "good"
            } finally {
                Files.deleteIfExists(tmpFile)
            }
        }

        test("FileGrant.rewrittenPath redirects to different path") {
            val realFile = Files.createTempFile("nox_real_", ".txt")
            Files.writeString(realFile, "redirected content")
            val ctx =
                fileGrantContext(
                    PermissionResponse.Granted.FileGrant(rewrittenPath = realFile.toString()),
                )
            try {
                val result = runBlocking { FileModule.read(ctx, "/fake/path.txt") }
                result shouldBe "redirected content"
            } finally {
                Files.deleteIfExists(realFile)
            }
        }

        test("HttpGrant.httpsOnly blocks HTTP URLs") {
            val ctx = httpGrantContext(PermissionResponse.Granted.HttpGrant(httpsOnly = true))
            shouldThrow<SecurityException> {
                runBlocking { HttpModule.get(ctx, "http://example.com") }
            }.message shouldContain "HTTPS"
        }

        test("HttpGrant.allowedDomains blocks disallowed domains") {
            val ctx =
                httpGrantContext(
                    PermissionResponse.Granted.HttpGrant(allowedDomains = listOf("api.safe.com")),
                )
            shouldThrow<SecurityException> {
                runBlocking { HttpModule.get(ctx, "https://evil.com/data") }
            }.message shouldContain "not in allowed list"
        }

        test("HttpGrant.allowedPorts blocks disallowed ports") {
            val ctx =
                httpGrantContext(
                    PermissionResponse.Granted.HttpGrant(allowedPorts = listOf(443, 8080)),
                )
            shouldThrow<SecurityException> {
                runBlocking { HttpModule.get(ctx, "https://example.com:9999/data") }
            }.message shouldContain "port"
        }

        test("HttpGrant.allowedPorts uses default port 443 for HTTPS") {
            // Port 443 IS allowed, so this should NOT throw for the constraint check.
            // It will fail later on the actual HTTP request, but that's fine.
            val ctx =
                httpGrantContext(
                    PermissionResponse.Granted.HttpGrant(allowedPorts = listOf(443)),
                )
            // Using a URL that won't actually resolve to avoid real HTTP calls
            try {
                runBlocking { HttpModule.get(ctx, "https://localhost.invalid/test") }
            } catch (e: SecurityException) {
                throw e // Re-throw security exceptions since they mean the constraint failed
            } catch (_: Exception) {
                // Expected, connection failure, DNS failure, etc. The constraint passed.
            }
        }

        test("Env.get with granted permission returns env variable") {
            val result = runBlocking { EnvModule.get(grantAllContext(), "PATH") }
            result.isNotEmpty() shouldBe true
        }

        test("Env.get with denied permission throws SecurityException") {
            shouldThrow<SecurityException> {
                runBlocking { EnvModule.get(denyAllContext(), "SECRET_KEY") }
            }
        }

        test("EnvGrant.allowedVarNames blocks disallowed variables") {
            val ctx =
                envGrantContext(
                    PermissionResponse.Granted.EnvGrant(allowedVarNames = listOf("HOME", "USER")),
                )
            shouldThrow<SecurityException> {
                runBlocking { EnvModule.get(ctx, "SECRET_KEY") }
            }.message shouldContain "not in allowed list"
        }

        test("EnvGrant.allowedVarNames allows whitelisted variables") {
            val ctx =
                envGrantContext(
                    PermissionResponse.Granted.EnvGrant(allowedVarNames = listOf("PATH", "HOME")),
                )
            // Should not throw
            val result = runBlocking { EnvModule.get(ctx, "PATH") }
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

        test("selective context allows reads but denies writes") {
            val ctx =
                selectiveContext { request ->
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

        test("SecurityException includes deny reason from context") {
            val ctx = denyAllContext("sandbox policy: no file access for untrusted scripts")
            val ex =
                shouldThrow<SecurityException> {
                    runBlocking { FileModule.read(ctx, "/etc/passwd") }
                }
            ex.message shouldContain "sandbox policy: no file access for untrusted scripts"
        }
    })
