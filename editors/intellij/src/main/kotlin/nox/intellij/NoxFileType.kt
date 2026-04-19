package nox.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object NoxFileType : LanguageFileType(NoxLanguage) {
    override fun getName(): String = "Nox"

    override fun getDescription(): String = "Nox source file"

    override fun getDefaultExtension(): String = "nox"

    override fun getIcon(): Icon = NoxIcons.FILE
}
