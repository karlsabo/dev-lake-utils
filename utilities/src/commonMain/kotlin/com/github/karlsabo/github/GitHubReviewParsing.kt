package com.github.karlsabo.github

import com.github.karlsabo.tools.lenientJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun isApprovedHumanReview(element: JsonElement): Boolean {
    val obj = element.jsonObject
    val state = obj["state"]?.jsonPrimitive?.content
    val userObj = obj["user"]?.jsonObject
    val login = userObj?.get("login")?.jsonPrimitive?.content
    val type = userObj?.get("type")?.jsonPrimitive?.content
    return state.equals("APPROVED", ignoreCase = true) && !isBotUser(login, type)
}

internal fun latestHumanReviews(
    reviewsText: String,
): Map<String, ReviewStateValue> = lenientJson.parseToJsonElement(reviewsText)
    .jsonArray
    .mapNotNull(::humanReviewState)
    .associate { it.user to it.state }

private data class HumanReviewState(
    val user: String,
    val state: ReviewStateValue,
)

private fun humanReviewState(element: JsonElement): HumanReviewState? {
    val obj = element.jsonObject
    val userObj = obj["user"]?.jsonObject
    val login = userObj?.get("login")?.jsonPrimitive?.content
    val type = userObj?.get("type")?.jsonPrimitive?.content
    val state = obj["state"]?.jsonPrimitive?.content?.toReviewStateValue()

    return if (login != null && state != null && !isBotUser(login, type)) {
        HumanReviewState(login, state)
    } else {
        null
    }
}

private fun String.toReviewStateValue(): ReviewStateValue? = when (uppercase()) {
    "APPROVED" -> ReviewStateValue.APPROVED
    "CHANGES_REQUESTED" -> ReviewStateValue.CHANGES_REQUESTED
    "COMMENTED" -> ReviewStateValue.COMMENTED
    "PENDING" -> ReviewStateValue.PENDING
    "DISMISSED" -> ReviewStateValue.DISMISSED
    else -> null
}

internal fun isPendingReviewState(
    review: ReviewState,
): Boolean = review.state == ReviewStateValue.CHANGES_REQUESTED || review.state == ReviewStateValue.PENDING

internal fun isBotUser(login: String?, type: String?): Boolean {
    val normalizedLogin = login?.lowercase()
    return type.equals("Bot", ignoreCase = true) ||
        normalizedLogin?.endsWith("[bot]") == true ||
        normalizedLogin in knownAutomationAccounts()
}

private fun knownAutomationAccounts(): Set<String> = setOf("continuous-deployer")
