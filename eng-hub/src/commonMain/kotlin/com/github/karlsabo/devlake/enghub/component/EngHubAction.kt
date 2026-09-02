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
        fuzzyMatchRank(normalizedQuery, listOf(action.title) + action.keywords)?.let { rank ->
            RankedAction(action, rank, index)
        }
    }.sortedWith(compareBy<RankedAction>({ it.rank.kind }, { it.rank.distance }, { it.sourceIndex }))
        .map(RankedAction::action)
}

private data class RankedAction(
    val action: EngHubAction,
    val rank: FuzzyMatchRank,
    val sourceIndex: Int,
)
