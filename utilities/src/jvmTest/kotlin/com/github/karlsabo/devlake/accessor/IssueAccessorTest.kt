package com.github.karlsabo.devlake.accessor

import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import kotlinx.datetime.Clock.System
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.days

class IssueAccessorTest {

    @Test
    fun testGetIssues() {
        DataSourceManagerDb(loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)?.toDataSourceDbConfig()!!).use { dataSourceManager ->
            val accessor = IssueAccessorDb(dataSourceManager.getOrCreateDataSource())
            val issues = accessor.getIssues(1, 0)
            assertNotNull(issues)
            issues.forEach {
                println(it)
            }
        }
    }

    @Test
    fun testGetIssueById() {
        DataSourceManagerDb(loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)?.toDataSourceDbConfig()!!).use { dataSourceManager ->
            val accessor = IssueAccessorDb(dataSourceManager.getOrCreateDataSource())
            val issue = accessor.getIssuesByKey("PLAT-17351")
            assertNotNull(issue)
            println(issue)
        }
    }

    @Test
    fun testGetIssuesLast30DaysByAccountId() {
        DataSourceManagerDb(loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)?.toDataSourceDbConfig()!!).use { dataSourceManager ->
            val accessor = IssueAccessorDb(dataSourceManager.getOrCreateDataSource())
            val thirtyDaysAgo = System.now().minus(30.days)
            val issues = accessor.getIssuesByAssigneeIdAndAfterResolutionDate(
                "jira:JiraAccount:1:62664b41357b9f006e4a944e",
                thirtyDaysAgo,
            )
            assertNotNull(issues)
            issues.forEach {
                println(it)
            }
        }
    }
}
