package com.github.karlsabo.devlake.enghub.component

internal data class EngHubAction(
    val title: String,
    val keywords: List<String> = emptyList(),
    val onInvoke: () -> Unit,
)

internal fun filterEngHubActions(
    actions: List<EngHubAction>,
    query: String,
): List<EngHubAction> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return actions

    return actions.mapIndexedNotNull { index, action ->
        action.matchRank(normalizedQuery)?.let { rank -> RankedAction(action, rank, index) }
    }.sortedWith(compareBy<RankedAction>({ it.rank.kind }, { it.rank.distance }, { it.sourceIndex }))
        .map(RankedAction::action)
}

private data class RankedAction(
    val action: EngHubAction,
    val rank: MatchRank,
    val sourceIndex: Int,
)

private data class MatchRank(
    val kind: Int,
    val distance: Int,
)

private fun EngHubAction.matchRank(query: String): MatchRank? {
    val searchableValues = listOf(title) + keywords
    if (searchableValues.any { query in it.lowercase() }) return MatchRank(kind = 0, distance = 0)

    val closestDistance = searchableValues
        .flatMap { value -> value.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty) }
        .minOfOrNull { word -> levenshteinDistance(query, word) }

    return closestDistance?.takeIf { it <= MAX_FUZZY_DISTANCE }?.let { distance ->
        MatchRank(kind = 1, distance = distance)
    }
}

private fun levenshteinDistance(left: String, right: String): Int {
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftCharacter ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightCharacter ->
            val substitutionCost = if (leftCharacter == rightCharacter) 0 else 1
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + substitutionCost,
            )
        }
        previous = current
    }
    return previous[right.length]
}

private const val MAX_FUZZY_DISTANCE = 2
