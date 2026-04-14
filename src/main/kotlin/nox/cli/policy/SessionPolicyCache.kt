package nox.cli.policy

import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import java.nio.file.Path

/**
 * Tracks interactive decisions made during execution so the user isn't re-prompted.
 */
class SessionPolicyCache {
    // Accumulated directory-level allows
    private val allowedFileReadDirs = mutableSetOf<Path>()
    private val allowedFileWriteDirs = mutableSetOf<Path>()
    private val allowedFileDeleteDirs = mutableSetOf<Path>()
    private val allowedFileListDirs = mutableSetOf<Path>()
    private val allowedHttpDomains = mutableSetOf<String>()
    private val allowedHttpGetDomains = mutableSetOf<String>()
    private val allowedEnvVars = mutableSetOf<String>()
    private val allowedSysinfo = mutableSetOf<String>()
    private val allowedPluginActions = mutableSetOf<Pair<String, String>>()
    private val allowedPluginCats = mutableSetOf<String>()

    // Blanket allows
    private var blanketAllowFileRead = false
    private var blanketAllowFileWrite = false
    private var blanketAllowFileDelete = false
    private var blanketAllowHttp = false
    private var blanketAllowHttps = false
    private var blanketAllowEnv = false
    private var blanketAllowSysinfo = false
    private var blanketAllowPlugin = false

    // Blanket denies
    private var blanketDenyFileRead = false
    private var blanketDenyFileWrite = false
    private var blanketDenyHttp = false
    private var blanketDenyEnv = false
    private var blanketDenySysinfo = false
    private var blanketDenyPlugin = false

    // Extension filter
    private val allowedExtensions = mutableSetOf<String>()

    fun check(request: PermissionRequest): PermissionResponse? =
        when (request) {
            is PermissionRequest.File -> checkFile(request)
            is PermissionRequest.Http -> checkHttp(request)
            is PermissionRequest.Env -> checkEnv(request)
            is PermissionRequest.Plugin -> checkPlugin(request)
        }

    private fun checkFile(request: PermissionRequest.File): PermissionResponse? {
        val path =
            when (request) {
                is PermissionRequest.File.Read -> request.path
                is PermissionRequest.File.Write -> request.path
                is PermissionRequest.File.Append -> request.path
                is PermissionRequest.File.Delete -> request.path
                is PermissionRequest.File.List -> request.directory
                is PermissionRequest.File.Metadata -> request.path
                is PermissionRequest.File.CreateDirectory -> request.path
            }
        val canonical = Path.of(path).toAbsolutePath().normalize()

        val isRead =
            request is PermissionRequest.File.Read ||
                request is PermissionRequest.File.List ||
                request is PermissionRequest.File.Metadata
        val isWrite =
            request is PermissionRequest.File.Write ||
                request is PermissionRequest.File.Append ||
                request is PermissionRequest.File.CreateDirectory
        val isDelete = request is PermissionRequest.File.Delete

        // Check blanket denies
        if (isRead && blanketDenyFileRead) return PermissionResponse.Denied("All file reads denied this session")
        if ((isWrite || isDelete) && blanketDenyFileWrite) {
            return PermissionResponse.Denied("All file writes denied this session")
        }

        // Check blanket allows
        if (isRead && blanketAllowFileRead) return PermissionResponse.Granted.FileGrant()
        if (isWrite && blanketAllowFileWrite) return PermissionResponse.Granted.FileGrant()
        if (isDelete && blanketAllowFileDelete) return PermissionResponse.Granted.FileGrant()

        // Check extension allows
        if (allowedExtensions.isNotEmpty()) {
            val ext = canonical.fileName?.toString()?.substringAfterLast('.', "") ?: ""
            if (ext in allowedExtensions) return PermissionResponse.Granted.FileGrant()
        }

        // Check directory-level allows
        val dirs =
            when {
                isRead -> allowedFileReadDirs
                isWrite -> allowedFileWriteDirs
                isDelete -> allowedFileDeleteDirs
                else -> allowedFileListDirs
            }
        if (dirs.any { canonical.startsWith(it) }) return PermissionResponse.Granted.FileGrant()

        return null
    }

    private fun checkHttp(request: PermissionRequest.Http): PermissionResponse? {
        val url =
            when (request) {
                is PermissionRequest.Http.Get -> request.url
                is PermissionRequest.Http.Post -> request.url
                is PermissionRequest.Http.Put -> request.url
                is PermissionRequest.Http.Delete -> request.url
            }

        if (blanketDenyHttp) return PermissionResponse.Denied("All HTTP denied this session")
        if (blanketAllowHttp) return PermissionResponse.Granted.HttpGrant()
        if (blanketAllowHttps && url.startsWith("https://")) return PermissionResponse.Granted.HttpGrant()

        val domain = PermissionPolicy.extractDomain(url) ?: return null
        if (allowedHttpDomains.any { domain.equals(it, ignoreCase = true) }) {
            return PermissionResponse.Granted.HttpGrant()
        }
        if (request is PermissionRequest.Http.Get &&
            allowedHttpGetDomains.any { domain.equals(it, ignoreCase = true) }
        ) {
            return PermissionResponse.Granted.HttpGrant()
        }
        return null
    }

    private fun checkEnv(request: PermissionRequest.Env): PermissionResponse? =
        when (request) {
            is PermissionRequest.Env.ReadVar -> {
                if (blanketDenyEnv) {
                    PermissionResponse.Denied("All env access denied this session")
                } else if (blanketAllowEnv || request.name in allowedEnvVars) {
                    PermissionResponse.Granted.EnvGrant()
                } else {
                    null
                }
            }
            is PermissionRequest.Env.SystemInfo -> {
                if (blanketDenySysinfo) {
                    PermissionResponse.Denied("All sysinfo denied this session")
                } else if (blanketAllowSysinfo || request.property in allowedSysinfo) {
                    PermissionResponse.Granted.EnvGrant()
                } else {
                    null
                }
            }
        }

    private fun checkPlugin(request: PermissionRequest.Plugin): PermissionResponse? {
        if (blanketDenyPlugin) return PermissionResponse.Denied("All plugin actions denied this session")
        if (blanketAllowPlugin) return PermissionResponse.Granted.PluginGrant()
        if (request.category in allowedPluginCats) return PermissionResponse.Granted.PluginGrant()
        if ((request.category to request.action) in allowedPluginActions) {
            return PermissionResponse.Granted.PluginGrant()
        }
        return null
    }

    fun allowFileDir(
        dir: Path,
        read: Boolean = false,
        write: Boolean = false,
        delete: Boolean = false,
    ) {
        val canonical = dir.toAbsolutePath().normalize()
        if (read) allowedFileReadDirs.add(canonical)
        if (write) allowedFileWriteDirs.add(canonical)
        if (delete) allowedFileDeleteDirs.add(canonical)
    }

    fun allowFileExtension(ext: String) {
        allowedExtensions.add(ext.removePrefix("."))
    }

    fun allowAllFileReads() {
        blanketAllowFileRead = true
    }

    fun allowAllFileWrites() {
        blanketAllowFileWrite = true
    }

    fun allowAllFileDeletes() {
        blanketAllowFileDelete = true
    }

    fun denyAllFileReads() {
        blanketDenyFileRead = true
    }

    fun denyAllFileWrites() {
        blanketDenyFileWrite = true
    }

    fun allowHttpDomain(domain: String) {
        allowedHttpDomains.add(domain)
    }

    fun allowHttpGetDomain(domain: String) {
        allowedHttpGetDomains.add(domain)
    }

    fun allowAllHttp() {
        blanketAllowHttp = true
    }

    fun allowAllHttps() {
        blanketAllowHttps = true
    }

    fun denyAllHttp() {
        blanketDenyHttp = true
    }

    fun allowEnvVar(name: String) {
        allowedEnvVars.add(name)
    }

    fun allowAllEnv() {
        blanketAllowEnv = true
    }

    fun denyAllEnv() {
        blanketDenyEnv = true
    }

    fun allowSysinfo(prop: String) {
        allowedSysinfo.add(prop)
    }

    fun allowAllSysinfo() {
        blanketAllowSysinfo = true
    }

    fun denyAllSysinfo() {
        blanketDenySysinfo = true
    }

    fun allowPluginAction(
        category: String,
        action: String,
    ) {
        allowedPluginActions.add(category to action)
    }

    fun allowPluginCat(category: String) {
        allowedPluginCats.add(category)
    }

    fun allowAllPlugins() {
        blanketAllowPlugin = true
    }

    fun denyAllPlugins() {
        blanketDenyPlugin = true
    }
}
