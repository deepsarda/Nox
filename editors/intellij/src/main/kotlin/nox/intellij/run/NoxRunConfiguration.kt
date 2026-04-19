package nox.intellij.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import org.jdom.Element
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

class NoxRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<RunConfiguration>(project, factory, name) {
    var scriptPath: String = ""
    var programArgs: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = NoxRunConfigurationEditor()

    override fun getState(
        executor: Executor,
        environment: ExecutionEnvironment,
    ): CommandLineState {
        if (scriptPath.isBlank()) throw ExecutionException("Script path is empty")
        val file = File(scriptPath)
        if (!file.exists()) throw ExecutionException("Script not found: $scriptPath")

        return object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val cmd =
                    GeneralCommandLine("nox", file.absolutePath)
                        .withWorkDirectory(file.parentFile)
                        .withCharset(Charsets.UTF_8)
                if (programArgs.isNotBlank()) cmd.addParameters(programArgs.split(" ").filter { it.isNotBlank() })
                val handler = OSProcessHandler(cmd)
                handler.setShouldDestroyProcessRecursively(true)
                return handler
            }
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "SCRIPT_PATH", scriptPath)
        JDOMExternalizerUtil.writeField(element, "PROGRAM_ARGS", programArgs)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        scriptPath = JDOMExternalizerUtil.readField(element, "SCRIPT_PATH", "")
        programArgs = JDOMExternalizerUtil.readField(element, "PROGRAM_ARGS", "")
    }
}

private class NoxRunConfigurationEditor : SettingsEditor<NoxRunConfiguration>() {
    private val panel = JPanel()

    override fun resetEditorFrom(s: NoxRunConfiguration) {}

    override fun applyEditorTo(s: NoxRunConfiguration) {}

    override fun createEditor(): JComponent = panel
}
