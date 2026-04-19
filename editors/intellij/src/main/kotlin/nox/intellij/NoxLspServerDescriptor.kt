package nox.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor

class NoxLspServerDescriptor(
    project: Project,
) : ProjectWideLspServerDescriptor(project, "Nox") {
    override fun isSupportedFile(file: VirtualFile): Boolean = file.extension == "nox"

    override fun createCommandLine(): GeneralCommandLine {
        val binary = NoxLspBinary.resolve() ?: error(NoxLspBinary.NOT_FOUND_MESSAGE)
        return GeneralCommandLine(binary).withCharset(Charsets.UTF_8)
    }
}
