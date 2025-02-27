package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.User
import java.sql.Connection

class FindAccountUtil {
}

fun findAccounts(connection: Connection, users: List<User>) {
    users.forEach { user ->
        println("Searching for ${user.name}")
        findAccountsByUser(connection, user.name)
    }
}

fun findAccountsByUser(connection: Connection, userFullname: String) {
    val parts = userFullname.lowercase().split(" ")

    // Create the SQL query
    val queryBuilder = StringBuilder("SELECT * FROM accounts WHERE ")
    for (i in parts.indices) {
        if (i > 0) {
            queryBuilder.append(" OR ")
        }
        queryBuilder.append("LOWER(email) LIKE ? OR LOWER(full_name) LIKE ? OR LOWER(user_name) LIKE ?")
    }
    connection.prepareStatement(queryBuilder.toString()).use { statement ->
        for (i in parts.indices) {
            statement.setString(i * 3 + 1, "%${parts[i]}%")
            statement.setString(i * 3 + 2, "%${parts[i]}%")
            statement.setString(i * 3 + 3, "%${parts[i]}%")
        }
        statement.executeQuery().use { resultSet ->
            while (resultSet.next()) {
                val username = resultSet.getString("user_name").lowercase()
                val email = resultSet.getString("email").lowercase()
                val fullname = resultSet.getString("full_name").lowercase()

                // email match percentage
                var emailMatchPercentage = 0f
                var fullnameMatchPercentage = 0f
                var usernameMatchPercentage = 0f
                parts.forEach { part->
                    if (email.contains(part)) {
                        emailMatchPercentage += 1f / parts.size
                    }
                    if(fullname.contains(part)) {
                        fullnameMatchPercentage += 1f / parts.size
                    }
                    if(username.contains(part)) {
                        usernameMatchPercentage += 1f / parts.size
                    }
                }

                val matchPercentage = (emailMatchPercentage + fullnameMatchPercentage + usernameMatchPercentage) / 3

                println("Match percentage $matchPercentage, username=$username, id=${resultSet.getString("id")}, email=$email, full_name=$fullname")
            }
        }
    }
}
