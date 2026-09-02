package com.github.karlsabo.devlake.enghub.component

internal data class FuzzyMatchRank(
    val kind: Int,
    val distance: Int,
)

internal fun fuzzyMatchRank(
    query: String,
    searchableValues: List<String>,
): FuzzyMatchRank? {
    val normalizedQuery = query.trim().lowercase()
    return when {
        normalizedQuery.isEmpty() -> FuzzyMatchRank(kind = 0, distance = 0)
        searchableValues.any { normalizedQuery in it.lowercase() } -> FuzzyMatchRank(kind = 0, distance = 0)
        else -> fuzzyDistanceRank(normalizedQuery, searchableValues)
    }
}

private fun fuzzyDistanceRank(query: String, searchableValues: List<String>): FuzzyMatchRank? {
    val closestDistance = searchableValues
        .flatMap { value -> value.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty) }
        .minOfOrNull { word -> levenshteinDistance(query, word) }
    return closestDistance?.takeIf { it <= MAX_FUZZY_DISTANCE }?.let { distance ->
        FuzzyMatchRank(kind = 1, distance = distance)
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
