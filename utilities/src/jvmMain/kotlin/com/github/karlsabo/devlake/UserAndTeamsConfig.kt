package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.Team
import com.github.karlsabo.devlake.accessor.TeamUser
import com.github.karlsabo.dto.User
import kotlinx.serialization.Serializable

@Serializable
data class UserAndTeamsConfig(
    val users: List<User>,
    val teams: List<Team>,
    val teamUsers: List<TeamUser>,
)
