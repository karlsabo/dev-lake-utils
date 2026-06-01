package com.github.karlsabo.tools

import com.github.karlsabo.pagerduty.PagerDutyIncident
import kotlinx.datetime.Clock
import kotlin.time.Duration

internal suspend fun fetchPagerDutyIncidents(
    sources: SummarySources,
    duration: Duration,
): List<PagerDutyIncident>? {
    if (sources.pagerDutyApi == null || sources.pagerDutyServiceIds.isEmpty()) return null

    val alertList = mutableListOf<PagerDutyIncident>()
    sources.pagerDutyServiceIds.forEach { serviceId ->
        alertList += sources.pagerDutyApi.getServicePages(
            serviceId,
            Clock.System.now().minus(duration),
            Clock.System.now(),
        )
    }
    return alertList
}
