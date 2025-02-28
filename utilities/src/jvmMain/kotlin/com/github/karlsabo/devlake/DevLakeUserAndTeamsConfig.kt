package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.Team
import com.github.karlsabo.devlake.accessor.TeamUser
import com.github.karlsabo.devlake.accessor.User
import com.github.karlsabo.devlake.accessor.UserAccount
import kotlinx.serialization.Serializable

@Serializable
data class DevLakeUserAndTeamsConfig(
    val users: List<User>,
    val userAccounts: List<UserAccount>,
    val teams: List<Team>,
    val teamUsers: List<TeamUser>
)
