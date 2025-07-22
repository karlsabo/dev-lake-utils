package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.Team
import com.github.karlsabo.devlake.accessor.TeamUser
import com.github.karlsabo.devlake.accessor.UserAccount
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import com.github.karlsabo.dto.User
import com.github.karlsabo.tools.getApplicationDirectory
import com.github.karlsabo.tools.lenientJson
import io.ktor.utils.io.readText
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import java.sql.Connection

const val DEV_LAKE_APP_NAME = "DevLakeUtils"
val devLakeDataSourceDbConfigPath = Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "dev-lake-datasource-db-config.json")
val textSummarizerConfigPath = Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "text-summarizer-openai-config.json")
val jiraConfigPath = Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "jira-rest-config.json")
val gitHubConfigPath = Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "github-config.json")

fun main() {
    val dataSourceConfigNoSecrets = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)
    if (dataSourceConfigNoSecrets == null) {
        println("Failed to load dev lake data source db config, populate $devLakeDataSourceDbConfigPath")
        return
    }
    val devLakeDataSourceDbConfig = dataSourceConfigNoSecrets.toDataSourceDbConfig()
    val userAndTeamConfig = loadUserAndTeamConfig()
    if (userAndTeamConfig == null || userAndTeamConfig.users.isEmpty() || userAndTeamConfig.users[0].id == "Example1 ID") {
        println("Users config file $devLakeUserAndTeamsConfigPath does not exist, please populate it.")
        if (!SystemFileSystem.exists(devLakeUserAndTeamsConfigPath)) {
            saveUserConfig(
                UserAndTeamsConfig(
                    listOf(
                        User(
                            id = "Example1 ID",
                            email = "example1@example.local",
                            name = "Example Name",
                        ),
                        User(
                            id = "Example2 ID",
                            email = "example2@example.local",
                            name = "Example2 Name",
                        )
                    ),
                    listOf(
                        UserAccount(
                            userId = "Example1 ID",
                            accountId = "github:GithubAccount:1:1234",
                        ),
                        UserAccount(
                            userId = "Example2 ID",
                            accountId = "github:GithubAccount:1:1235",
                        )
                    ),
                    listOf(
                        Team(
                            id = 1,
                            name = "Example Team",
                            alias = "example-team",
                        ),
                        Team(
                            id = 2,
                            name = "Example Team 2",
                            alias = "example-team-2",
                        ),
                    ),
                    listOf(
                        TeamUser(
                            teamId = 1,
                            userId = "Example1 ID",
                        ),
                        TeamUser(
                            teamId = 2,
                            userId = "Example2 ID",
                        )
                    ),
                )
            )
        }
        return
    }

    DataSourceManagerDb(devLakeDataSourceDbConfig).use { dataSource ->
        dataSource.getOrCreateDataSource().connection.use { connection ->
            userAndTeamConfig.teams.forEach {
                createTeam(connection, it)
            }

            userAndTeamConfig.users.forEach { user ->
                createUser(
                    connection,
                    user,
                    userAndTeamConfig.teamUsers.filter { user.id == it.userId }.toList(),
                    userAndTeamConfig.userAccounts.filter { user.id == it.userId }.toList(),
                )
            }

            deleteOldTeams(connection, userAndTeamConfig.teams)
        }
    }
}

fun deleteOldTeams(connection: Connection, teams: Collection<Team>) {
    connection.prepareStatement(
        """
        DELETE FROM 
            teams
        WHERE 
            id NOT IN (${teams.joinToString(",") { "?" }})
        """.trimIndent()
    ).use { deleteOldTeamsStatement ->
        teams.forEachIndexed { index, team ->
            deleteOldTeamsStatement.setString(index + 1, team.id.toString())
        }
        val executeUpdate = deleteOldTeamsStatement.executeUpdate()
        println("Deleted old teams count = $executeUpdate")
    }
}

private fun createUser(
    connection: Connection,
    user: User,
    teamUsers: Collection<TeamUser>,
    userAccounts: Collection<UserAccount>
) {
    // create user
    connection.prepareStatement(
        """
        INSERT INTO
            users (id, email, name)
        VALUES
            (?, ?, ?)
        ON DUPLICATE KEY UPDATE
            id = VALUES(id),
            email = VALUES(email),
            name = VALUES(name)
        """.trimIndent()
    ).use { ps ->
        ps.setString(1, user.id)
        ps.setString(2, user.email)
        ps.setString(3, user.name)
        val executeUpdate = ps.executeUpdate()
        println("Updated user $user count = $executeUpdate")
    }

    // create team_users mapping
    teamUsers.forEach { team ->
        if (team.userId != user.id) throw RuntimeException("Mismatch in data, $user and $team don't have the same user id")
        connection.prepareStatement(
            """
            INSERT INTO
                team_users (team_id, user_id)
            VALUES
                (?, ?)
            ON DUPLICATE KEY UPDATE
                team_id = VALUES(team_id),
                user_id = VALUES(user_id)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, team.teamId)
            ps.setString(2, team.userId)
            val executeUpdate = ps.executeUpdate()
            println("Added team_users for ${user.email} count = $executeUpdate")
        }
    }
    connection.prepareStatement(
        """
        DELETE FROM
            team_users
        WHERE
            user_id = ?
            AND team_id NOT IN (${teamUsers.joinToString(",") { "?" }})
        """.trimIndent()
    ).use {
        it.setString(1, user.id)
        teamUsers.forEachIndexed { index, team ->
            it.setLong(index + 2, team.teamId)
        }
        val executeUpdate = it.executeUpdate()
        println("Deleted old team_users for ${user.email} count = $executeUpdate")
    }

    // create user_accounts mapping
    userAccounts.forEach { account ->
        if (user.id != account.userId) throw RuntimeException("Mismatch in data, $user and $account don't have the same user id")
        connection.prepareStatement(
            """
            INSERT INTO
                user_accounts (user_id, account_id)
            VALUES
                (?, ?)
            ON DUPLICATE KEY UPDATE
                user_id = VALUES(user_id),
                account_id = VALUES(account_id)                            
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, account.userId)
            ps.setString(2, account.accountId)
            val executeUpdate = ps.executeUpdate()
            println("Added user_accounts $account for ${user.email} count = $executeUpdate")
        }
    }
    connection.prepareStatement(
        """
        DELETE FROM
            user_accounts
        WHERE
            user_id = ?
            AND account_id NOT IN (${userAccounts.joinToString(",") { "?" }})
        """.trimIndent()
    ).use {
        it.setString(1, user.id)
        userAccounts.forEachIndexed { index, account ->
            it.setString(index + 2, account.accountId)
        }
        val executeUpdate = it.executeUpdate()
        println("Deleted old user_accounts for ${user.email} count = $executeUpdate")
    }
}

private val devLakeUserAndTeamsConfigPath = Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "users-and-teams.json")
fun loadUserAndTeamConfig(): UserAndTeamsConfig? {
    if (!SystemFileSystem.exists(devLakeUserAndTeamsConfigPath)) {
        return null
    }
    return try {
        lenientJson.decodeFromString(
            UserAndTeamsConfig.serializer(),
            SystemFileSystem.source(devLakeUserAndTeamsConfigPath).buffered().readText(),
        )
    } catch (error: Exception) {
        println("Failed to load user config: $error")
        return null
    }
}

private fun saveUserConfig(userConfig: UserAndTeamsConfig) {
    SystemFileSystem.sink(devLakeUserAndTeamsConfigPath).buffered().use {
        it.writeString(
            lenientJson.encodeToString(
                UserAndTeamsConfig.serializer(),
                userConfig,
            )
        )
    }
}

private fun createTeam(connection: Connection, team: Team) {
    if (!isTeamInTheDb(connection, team.id)) insertTeam(connection, team)
    println("Created team $team")
}

fun insertTeam(connection: Connection, team: Team) {
    connection.prepareStatement(
        """
            INSERT INTO teams
                (id, name, alias)
            VALUES
                (?, ?, ?)
        """.trimIndent()
    ).use { insertTeamStatement ->
        insertTeamStatement.setString(1, team.id.toString())
        insertTeamStatement.setString(2, team.name)
        insertTeamStatement.setString(3, team.alias)
        if (insertTeamStatement.executeUpdate() != 1) throw IllegalStateException("Failed to insert the new team")
    }
}

private fun isTeamInTheDb(connection: Connection, teamId: Long): Boolean {
    connection.prepareStatement(
        """
        SELECT 1 FROM teams
        WHERE id = ?
        """.trimIndent()
    ).use { selectAppSecTeam ->
        selectAppSecTeam.setString(1, teamId.toString())
        selectAppSecTeam.executeQuery().use { appSecTeamResultSet ->
            return appSecTeamResultSet.next()
        }
    }
}
