package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.devlake.tools.loadUserMetricPublisherConfig
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlin.time.measureTime


fun main(): Unit = runBlocking {
    val config = loadUserMetricPublisherConfig()
    val dataSourceConfig = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)!!
    val dataSourceManager = DataSourceManagerDb(dataSourceConfig.toDataSourceDbConfig())
    val metrics = mutableListOf<UserMetrics>()
    val jobs = config.userIds.map { userId ->
        async(Dispatchers.IO) {
            measureTime {
                val userMetrics = createUserMetrics(userId, dataSourceManager)
                synchronized(metrics) {
                    metrics.add(userMetrics)
                }
            }.also {
                println("Time to load metrics for ${userId}: $it")
            }
        }
    }
    jobs.joinAll()

    metrics.forEach {
        println(
            "📢 *Weekly PR & Issue Summary* 🚀 (${it.userId})\n"
                    + it.toSlackMarkdown()
                    + "\n"
                    + config.metricInformationPostfix
        )
    }
}
