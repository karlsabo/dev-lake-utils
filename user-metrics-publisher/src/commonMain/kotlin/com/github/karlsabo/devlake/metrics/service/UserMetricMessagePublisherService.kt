package com.github.karlsabo.devlake.metrics.service

import com.github.karlsabo.devlake.metrics.UserMetricPublisherConfig
import me.tatarka.inject.annotations.Inject

open class UserMetricMessagePublisherService @Inject constructor(
    private val config: UserMetricPublisherConfig,
) {
    open suspend fun publishMessage(message: SlackMessage): Boolean {
        return ZapierMetricService.sendMessage(message, config.zapierMetricUrl)
    }
}
