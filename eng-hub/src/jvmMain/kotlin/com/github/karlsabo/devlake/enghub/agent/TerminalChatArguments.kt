package com.github.karlsabo.devlake.enghub.agent

/** Command-line options for the terminal chat launcher. */
internal data class TerminalChatArguments(
    val mcpConfigPath: String? = null,
) {
    companion object {
        const val MCP_CONFIG_OPTION = "--mcp-config"
        const val USAGE = "Usage: terminalChat [--mcp-config /path/to/config.json]"

        fun parse(args: Array<out String>): TerminalChatArguments {
            var mcpConfigPath: String? = null
            var index = 0
            while (index < args.size) {
                val argument = args[index]
                when {
                    argument == MCP_CONFIG_OPTION -> {
                        require(mcpConfigPath == null) { "$MCP_CONFIG_OPTION may be provided only once." }
                        require(index + 1 < args.size) { "Missing value for $MCP_CONFIG_OPTION." }
                        index++
                        mcpConfigPath = requirePath(args[index])
                    }

                    argument.startsWith("$MCP_CONFIG_OPTION=") -> {
                        require(mcpConfigPath == null) { "$MCP_CONFIG_OPTION may be provided only once." }
                        mcpConfigPath = requirePath(argument.removePrefix("$MCP_CONFIG_OPTION="))
                    }

                    else -> throw IllegalArgumentException("Unknown argument: $argument")
                }
                index++
            }
            return TerminalChatArguments(mcpConfigPath)
        }

        private fun requirePath(value: String): String {
            require(value.isNotBlank()) { "MCP config path must not be blank." }
            return value
        }
    }
}
