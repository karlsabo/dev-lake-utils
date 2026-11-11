package com.github.karlsabo.devlake.enghub.agent

/** Command-line options shared by the Eng Hub agent launchers. */
internal data class TerminalChatArguments(
    val mcpConfigPath: String? = null,
    val lmStudioBaseUrl: String = LM_STUDIO_BASE_URL,
    val lmStudioApiKeyPath: String? = null,
) {
    companion object {
        const val MCP_CONFIG_OPTION = "--mcp-config"
        const val LM_STUDIO_BASE_URL_OPTION = "--lm-studio-base-url"
        const val LM_STUDIO_API_KEY_FILE_OPTION = "--lm-studio-api-key-file"
        val USAGE: String = usage("terminalChat")

        /** The usage line for the launcher task named [taskName]. */
        fun usage(taskName: String): String = "Usage: $taskName [--mcp-config /path/to/config.json] " +
            "[--lm-studio-base-url http://host:1234/v1] " +
            "[--lm-studio-api-key-file /path/to/api-key]"

        fun parse(args: Array<out String>): TerminalChatArguments {
            var mcpConfigPath: String? = null
            var lmStudioBaseUrl = LM_STUDIO_BASE_URL
            var lmStudioBaseUrlProvided = false
            var lmStudioApiKeyPath: String? = null
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

                    argument == LM_STUDIO_BASE_URL_OPTION -> {
                        require(!lmStudioBaseUrlProvided) {
                            "$LM_STUDIO_BASE_URL_OPTION may be provided only once."
                        }
                        require(index + 1 < args.size) { "Missing value for $LM_STUDIO_BASE_URL_OPTION." }
                        index++
                        lmStudioBaseUrl = requireBaseUrl(args[index])
                        lmStudioBaseUrlProvided = true
                    }

                    argument.startsWith("$LM_STUDIO_BASE_URL_OPTION=") -> {
                        require(!lmStudioBaseUrlProvided) {
                            "$LM_STUDIO_BASE_URL_OPTION may be provided only once."
                        }
                        lmStudioBaseUrl =
                            requireBaseUrl(argument.removePrefix("$LM_STUDIO_BASE_URL_OPTION="))
                        lmStudioBaseUrlProvided = true
                    }

                    argument == LM_STUDIO_API_KEY_FILE_OPTION -> {
                        require(lmStudioApiKeyPath == null) {
                            "$LM_STUDIO_API_KEY_FILE_OPTION may be provided only once."
                        }
                        require(index + 1 < args.size) { "Missing value for $LM_STUDIO_API_KEY_FILE_OPTION." }
                        index++
                        lmStudioApiKeyPath = requireApiKeyFilePath(args[index])
                    }

                    argument.startsWith("$LM_STUDIO_API_KEY_FILE_OPTION=") -> {
                        require(lmStudioApiKeyPath == null) {
                            "$LM_STUDIO_API_KEY_FILE_OPTION may be provided only once."
                        }
                        lmStudioApiKeyPath =
                            requireApiKeyFilePath(argument.removePrefix("$LM_STUDIO_API_KEY_FILE_OPTION="))
                    }

                    else -> throw IllegalArgumentException("Unknown argument: $argument")
                }
                index++
            }
            return TerminalChatArguments(mcpConfigPath, lmStudioBaseUrl, lmStudioApiKeyPath)
        }

        private fun requirePath(value: String): String {
            require(value.isNotBlank()) { "MCP config path must not be blank." }
            return value
        }

        private fun requireBaseUrl(value: String): String {
            require(value.isNotBlank()) { "LM Studio base URL must not be blank." }
            return value
        }

        private fun requireApiKeyFilePath(value: String): String {
            require(value.isNotBlank()) { "LM Studio API key file path must not be blank." }
            return value
        }
    }
}
