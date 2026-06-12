package nox.intellij

import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Resolves the `nox-lsp` binary for both the JetBrains LSP and LSP4IJ adapters.
 * Search order:
 *   1. Settings panel override (`lspPath`)
 *   2. `nox.lsp.path` system property
 *   3. `NOX_LSP` env var
 *   4. `~/.nox/bin/nox-lsp` (or `.exe` on Windows)
 *   5. System `PATH`
 *
 * If not found in any of the above, it will trigger an automatic download of the latest release.
 */
object NoxLspBinary {
    fun resolve(): String? {
        // Settings override
        runCatching { NoxSettings.instance().lspPath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // System property
        System.getProperty("nox.lsp.path")?.takeIf { it.isNotBlank() }?.let { return it }

        // Environment variable
        System.getenv("NOX_LSP")?.takeIf { it.isNotBlank() }?.let { return it }

        // Default user-local directory (~/.nox/bin)
        val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "nox-lsp.exe"
        } else {
            "nox-lsp"
        }
        val userLocalFile = File(File(System.getProperty("user.home"), ".nox"), "bin/$exe")
        if (userLocalFile.canExecute()) {
            return userLocalFile.absolutePath
        }

        // System PATH
        System
            .getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.map { File(it, exe) }
            ?.firstOrNull { it.canExecute() }
            ?.let { return it.absolutePath }

        // Auto-download fallback
        synchronized(this) {
            if (userLocalFile.canExecute()) {
                return userLocalFile.absolutePath
            }
            try {
                val downloaded = downloadAndInstallLatest(null)
                if (downloaded != null && File(downloaded).canExecute()) {
                    return downloaded
                }
            } catch (e: Exception) {
                // Log and return null so the IDE shows the default not found error
                e.printStackTrace()
            }
        }

        return null
    }

    /**
     * Downloads and installs the latest Nox release.
     * Returns the absolute path to the downloaded `nox-lsp` binary.
     */
    fun downloadAndInstallLatest(progressReporter: ((String) -> Unit)?): String? {
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.startsWith("win")
        
        val assetSuffix = when {
            isWindows -> "windows-x64"
            osName.startsWith("mac") || osName.contains("darwin") -> "macos-arm64"
            else -> "linux-x64" // default fallback
        }

        val noxHome = File(System.getProperty("user.home"), ".nox")
        val noxBinDir = File(noxHome, "bin")
        if (!noxHome.exists()) noxHome.mkdirs()
        if (!noxBinDir.exists()) noxBinDir.mkdirs()

        progressReporter?.invoke("Checking for latest Nox release on GitHub...")
        
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/deepsarda/Nox/releases"))
            .header("User-Agent", "Nox-IntelliJ")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("GitHub API returned HTTP ${response.statusCode()}")
        }

        val releasesJson = response.body()
        
        // Find the first tag_name that starts with v, excluding vscode- and intellij- releases
        val tagRegex = """\"tag_name\"\s*:\s*\"(v[0-9][^\"]+)\"""".toRegex()
        val matchResult = tagRegex.findAll(releasesJson).firstOrNull { match ->
            val tag = match.groupValues[1]
            !tag.contains("vscode-") && !tag.contains("intellij-")
        } ?: throw RuntimeException("No core Nox releases found on GitHub.")

        val latestTag = matchResult.groupValues[1]
        val version = latestTag.substring(1) // strip 'v'
        
        val releaseJson = releasesJson.substring(matchResult.range.first)
        
        val urlRegex = """\"browser_download_url\"\s*:\s*\"(https://[^\"]+)\"""".toRegex()
        val downloadUrl = urlRegex.findAll(releaseJson)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains(assetSuffix) }
            ?: throw RuntimeException("No asset found for platform $assetSuffix in release $latestTag")

        val archiveName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1)
        val tempArchive = File(noxHome, archiveName)

        progressReporter?.invoke("Downloading Nox v$version ($archiveName)...")
        
        val downloadRequest = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .header("User-Agent", "Nox-IntelliJ")
            .GET()
            .build()

        val downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream())
        if (downloadResponse.statusCode() != 200) {
            throw RuntimeException("Failed to download release asset: HTTP ${downloadResponse.statusCode()}")
        }

        downloadResponse.body().use { input ->
            FileOutputStream(tempArchive).use { output ->
                input.copyTo(output)
            }
        }

        progressReporter?.invoke("Extracting package...")
        try {
            if (isWindows) {
                unzip(tempArchive, noxHome, version, assetSuffix)
            } else {
                untar(tempArchive, noxHome, version, assetSuffix)
            }
        } finally {
            if (tempArchive.exists()) {
                tempArchive.delete()
            }
        }

        // Write version file
        File(noxHome, "version").writeText(latestTag)

        val exe = if (isWindows) "nox-lsp.exe" else "nox-lsp"
        val binaryFile = File(noxBinDir, exe)
        return if (binaryFile.canExecute()) {
            binaryFile.absolutePath
        } else {
            null
        }
    }

    private fun unzip(zipFile: File, targetDir: File, version: String, assetSuffix: String) {
        val binDir = File(targetDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        val expectedSubDir = "nox-$version-$assetSuffix"
        
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val pathParts = entry.name.split("/")
                    if (pathParts.size >= 3 && pathParts[0] == expectedSubDir && pathParts[1] == "bin") {
                        val fileName = pathParts.last()
                        val destFile = File(binDir, fileName)
                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun untar(tarGzFile: File, targetDir: File, version: String, assetSuffix: String) {
        val tempDir = File(targetDir, "temp_untar")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        
        val process = ProcessBuilder("tar", "-xzf", tarGzFile.absolutePath, "-C", tempDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorMsg = process.inputStream.bufferedReader().readText()
            throw RuntimeException("tar extraction failed with exit code $exitCode: $errorMsg")
        }
        
        val binDir = File(targetDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        
        val extractedBinDir = File(tempDir, "nox-$version-$assetSuffix/bin")
        if (extractedBinDir.exists() && extractedBinDir.isDirectory) {
            extractedBinDir.listFiles()?.forEach { file ->
                val destFile = File(binDir, file.name)
                file.copyTo(destFile, overwrite = true)
                destFile.setExecutable(true, false)
            }
        }
        
        tempDir.deleteRecursively()
    }

    const val NOT_FOUND_MESSAGE: String =
        "nox-lsp binary not found. Set `nox.lsp.path` in settings, the `NOX_LSP` env var, " +
            "or put nox-lsp on your PATH."
}
