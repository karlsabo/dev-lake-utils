package com.github.karlsabo.devlake.enghub.agent

import java.io.BufferedReader

/** Terminal input and output backed by the process standard streams. */
internal class SystemTerminalIO : TerminalIO {
    private val reader: BufferedReader = System.`in`.bufferedReader()

    override fun readLine(): String? = reader.readLine()

    override fun write(text: String) {
        System.out.print(text)
        System.out.flush()
    }
}
