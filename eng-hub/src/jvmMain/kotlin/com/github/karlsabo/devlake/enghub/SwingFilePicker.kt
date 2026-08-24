package com.github.karlsabo.devlake.enghub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

actual fun createFilePicker(): FilePicker = SwingFilePicker()

private class SwingFilePicker : FilePicker {
    override suspend fun pickFilePath(title: String): String? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            selectedFile = File(System.getProperty("user.home"), "github-secret.json")
        }
        val result = chooser.showSaveDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
}
