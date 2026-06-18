package com.github.karlsabo.tools.formatting

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal const val RECENT_ACTIVITY_DAYS = 14

internal fun recentActivityCutoff(): Instant = Clock.System.now().minus(RECENT_ACTIVITY_DAYS.days)
