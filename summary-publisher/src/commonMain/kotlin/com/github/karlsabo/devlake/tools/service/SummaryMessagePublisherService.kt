package com.github.karlsabo.devlake.tools.service

import com.github.karlsabo.devlake.tools.ZapierSummaryPublisher
import me.tatarka.inject.annotations.Inject

class SummaryMessagePublisherService @Inject constructor(
    private val zapierSummaryPublisher: ZapierSummaryPublisher,
) {
    suspend fun publishSummary(summary: ZapierProjectSummary): Boolean {
        return zapierSummaryPublisher.publishSummary(summary)
    }
}
