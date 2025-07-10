package com.github.karlsabo.devlake.accessor

interface UserAccessor {
    fun getUsers(): List<User>
    fun getUserByEmail(email: String): User?
}

