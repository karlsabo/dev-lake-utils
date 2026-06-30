package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.common.datetime.DateTimeFormatting
import com.github.karlsabo.dto.User
import com.github.karlsabo.linear.LinearRestApi
import com.github.karlsabo.linear.config.loadLinearConfig
import com.github.karlsabo.tools.linearConfigPath
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlin.time.measureTime

/**
 * Demo that prints Linear issues completed by a user grouped by project and milestone.
 *
 * Usage:
 * ./gradlew :user-metrics-publisher:runUserLinearProjectsDemo \
 *   --args="--user=<linear-user-id> --start=YYYY-MM-DD --end=YYYY-MM-DD"
 */
fun main(args: Array<String>): Unit = runBlocking {
    val demoArgs = parseUserLinearProjectsDemoArguments(args)
    val linearApi = LinearRestApi(loadLinearConfig(linearConfigPath))

    println(
        "Finding Linear issues resolved by user: " +
            "${demoArgs.userId} (${demoArgs.startArgument} to ${demoArgs.endArgument})",
    )

    val executionTime = measureTime {
        val user = User(id = demoArgs.userId, name = demoArgs.userId, linearId = demoArgs.userId)
        val resolvedIssues = linearApi.getIssuesResolved(user, demoArgs.startDate, demoArgs.endDate)
        println("Found ${resolvedIssues.size} resolved issues\n")

        if (resolvedIssues.isEmpty()) {
            println("No resolved issues found for user ${demoArgs.userId} in the specified time range")
            return@measureTime
        }

        println(renderUserLinearProjectsMarkdown(resolvedIssues))
    }

    println("\nExecution time: $executionTime")
}

internal data class UserLinearProjectsDemoArguments(
    val userId: String,
    val startArgument: String,
    val endArgument: String,
    val startDate: Instant,
    val endDate: Instant,
)

internal fun parseUserLinearProjectsDemoArguments(args: Array<String>): UserLinearProjectsDemoArguments {
    val userId = requiredArgumentValue(args, "--user", "<linear-user-id>")
    val startArgument = requiredArgumentValue(args, "--start", "YYYY-MM-DD")
    val endArgument = requiredArgumentValue(args, "--end", "YYYY-MM-DD")

    val startDate = parseDateArgument("--start", startArgument)
    val endDate = parseDateArgument("--end", endArgument).plus(1.days).minus(1.nanoseconds)
    require(startDate <= endDate) { "--start must be on or before --end" }

    return UserLinearProjectsDemoArguments(
        userId = userId,
        startArgument = startArgument,
        endArgument = endArgument,
        startDate = startDate,
        endDate = endDate,
    )
}

private fun requiredArgumentValue(
    args: Array<String>,
    flag: String,
    placeholder: String,
): String = args.find { it.startsWith("$flag=") }
    ?.substringAfter("=")
    ?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("No $flag=$placeholder provided")

private fun parseDateArgument(flag: String, value: String): Instant = try {
    DateTimeFormatting.parseDateOnlyToInstant(value)
} catch (exception: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid $flag=YYYY-MM-DD value: $value", exception)
}
