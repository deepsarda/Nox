package nox.intellij

import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Ships the Nox TextMate grammar (mirrored from the VS Code extension at build
 * time) so the bundled TextMate plugin handles keyword/comment/string coloring.
 * LSP semantic tokens overlay on top for identifier-level highlighting.
 */
class NoxTextMateBundleProvider : TextMateBundleProvider {
    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> {
        val path = bundlePath
        print("NoxTextMateBundleProvider: bundlePath=$path")
        if (path == null) return emptyList()
        return listOf(TextMateBundleProvider.PluginBundle("Nox", path))
    }

    companion object {
        private const val RESOURCE_ROOT = "Nox.tmbundle"
        private val BUNDLE_FILES =
            listOf(
                "package.json",
                "syntaxes/nox.tmLanguage.json",
                "syntaxes/noxc.tmLanguage.json",
            )

        private val bundlePath: Path? by lazy { extract() }

        private fun extract(): Path? {
            val target = Files.createTempDirectory("nox-tmbundle-")
            val cl = NoxTextMateBundleProvider::class.java.classLoader
            var copied = 0
            for (rel in BUNDLE_FILES) {
                val out = target.resolve(rel)
                Files.createDirectories(out.parent)
                cl.getResourceAsStream("$RESOURCE_ROOT/$rel")?.use { input ->
                    Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
                    copied++
                }
            }
            return target.takeIf { copied > 0 }
        }
    }
}
