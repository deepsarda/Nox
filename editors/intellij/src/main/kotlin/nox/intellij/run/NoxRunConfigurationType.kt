package nox.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import nox.intellij.NoxIcons
import javax.swing.Icon

class NoxRunConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "Nox"

    override fun getConfigurationTypeDescription(): String = "Runs a Nox program via the `nox` CLI"

    override fun getIcon(): Icon = NoxIcons.FILE

    override fun getId(): String = "NoxRunConfiguration"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(NoxRunConfigurationFactory(this))
}
