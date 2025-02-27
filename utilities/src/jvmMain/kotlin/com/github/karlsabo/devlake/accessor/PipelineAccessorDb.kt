package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.toKotlinInstant
import javax.sql.DataSource

class PipelineAccessorDb(private val dataSource: DataSource) : PipelineAccessor {
    override fun getPipelines(limit: Int, offset: Int): List<Pipeline> {
        val pipelines = mutableListOf<Pipeline>()
        val query =
            """
            SELECT
                id, created_at, updated_at, name, blueprint_id, total_tasks, finished_tasks, began_at, finished_at, 
                status, message, spent_seconds, stage, plan, skip_on_fail, error_name, full_sync, skip_collectors, time_after
            FROM
                _devlake_pipelines
            ORDER BY
                created_at DESC
            LIMIT 
                ? 
            OFFSET 
                ?
            """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(query).use { ps ->
                ps.setInt(1, limit)
                ps.setInt(2, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        pipelines.add(
                            Pipeline(
                                id = rs.getLong("id"),
                                createdAt = rs.getTimestamp("created_at")?.toInstant()?.toKotlinInstant(),
                                updatedAt = rs.getTimestamp("updated_at")?.toInstant()?.toKotlinInstant(),
                                name = rs.getString("name"),
                                blueprintId = rs.getLong("blueprint_id"),
                                totalTasks = rs.getLong("total_tasks"),
                                finishedTasks = rs.getLong("finished_tasks"),
                                beganAt = rs.getTimestamp("began_at")?.toInstant()?.toKotlinInstant(),
                                finishedAt = rs.getTimestamp("finished_at")?.toInstant()?.toKotlinInstant(),
                                status = Status.valueOf(rs.getString("status")),
                                message = rs.getString("message"),
                                spentSeconds = rs.getLong("spent_seconds"),
                                stage = rs.getLong("stage"),
                                plan = rs.getString("plan"),
                                skipOnFail = rs.getBoolean("skip_on_fail"),
                                errorName = rs.getString("error_name"),
                                fullSync = rs.getBoolean("full_sync"),
                                skipCollectors = rs.getBoolean("skip_collectors"),
                                timeAfter = rs.getTimestamp("time_after")?.toInstant()?.toKotlinInstant(),
                            )
                        )
                    }
                }
            }
        }
        return pipelines
    }
}
