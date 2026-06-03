package com.github.karlsabo.tools.formatting

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

internal const val RECENT_ACTIVITY_DAYS = 14

internal fun recentActivityCutoff(): Instant = Clock.System.now().minus(RECENT_ACTIVITY_DAYS.days)
