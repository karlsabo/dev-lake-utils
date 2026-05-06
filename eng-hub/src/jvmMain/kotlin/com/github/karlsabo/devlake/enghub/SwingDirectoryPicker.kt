package com.github.karlsabo.devlake.enghub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

actual fun createDirectoryPicker(): DirectoryPicker = SwingDirectoryPicker()

private class SwingDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            selectedFile = File(System.getProperty("user.home"))
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
}
