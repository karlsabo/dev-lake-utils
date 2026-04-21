package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.tools.service.ZapierProjectSummary
import com.github.karlsabo.devlake.tools.service.ZapierService
import me.tatarka.inject.annotations.Inject

open class ZapierSummaryPublisher @Inject constructor(
    private val config: SummaryPublisherConfig,
) {
    open suspend fun publishSummary(summary: ZapierProjectSummary): Boolean {
        return ZapierService.sendSummary(summary, config.zapierSummaryUrl)
    }
}
