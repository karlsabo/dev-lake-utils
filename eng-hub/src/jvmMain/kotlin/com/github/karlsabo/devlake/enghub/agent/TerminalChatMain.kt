package com.github.karlsabo.devlake.enghub.agent

import com.nimblygames.llm.agent.buildOpenAICompatibleExecutor
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader

fun main() {
    val workingDirectory = System.getProperty("user.dir")
    val executor =
        buildOpenAICompatibleExecutor(
            LM_STUDIO_API_KEY,
            LM_STUDIO_BASE_URL,
            LM_STUDIO_HTTP_MAX_RETRIES,
        )

    try {
        runBlocking {
            val session = EngHubTerminalAgent.create(executor, workingDirectory)
            try {
                println("Eng Hub terminal chat. Type $EXIT_COMMAND or press Ctrl-D to quit.")
                println("Working directory: $workingDirectory")
                TerminalChat(session, SystemTerminalIO()).run()
            } finally {
                session.close()
            }
        }
    } finally {
        executor.close()
    }
}

private class SystemTerminalIO : TerminalIO {
    private val reader: BufferedReader = System.`in`.bufferedReader()

    override fun readLine(): String? = reader.readLine()

    override fun write(text: String) {
        System.out.print(text)
        System.out.flush()
    }
}
