package nox.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class NoxRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "NoxRunConfigurationFactory"

    override fun createTemplateConfiguration(project: Project): RunConfiguration = NoxRunConfiguration(project, this, "Nox")
}
