package com.github.karlsabo.pagerduty

import kotlinx.datetime.Instant

interface PagerDutyApi {
    /**
     * Get pages from a PagerDuty service within a specified time range.
     *
     * @param serviceId The ID of the PagerDuty service to query
     * @param startTimeInclusive The start time for the query range (inclusive)
     * @param endTimeExclusive The end time for the query range (exclusive)
     * @return A list of PagerDuty incidents (pages)
     */
    suspend fun getServicePages(
        serviceId: String,
        startTimeInclusive: Instant,
        endTimeExclusive: Instant,
    ): List<PagerDutyIncident>
}

