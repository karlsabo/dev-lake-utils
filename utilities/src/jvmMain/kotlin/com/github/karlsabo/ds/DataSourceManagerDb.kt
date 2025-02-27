package com.github.karlsabo.ds

import com.zaxxer.hikari.HikariDataSource
import io.ktor.utils.io.*
import io.ktor.utils.io.core.writeText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.sql.DataSource
import kotlin.io.use

data class DataSourceDbConfig(
    val jdbcUrl: String = "jdbc:mysql://localhost:4306/lake",
    val username: String = "merico",
    val password: String,
    val driverClassName: String = "com.mysql.cj.jdbc.Driver",
    val maximumPoolSize: Int = 30,
    val minimumIdle: Int = 2,
    val idleTimeoutMs: Long = 10000,
    val connectionTimeoutMs: Long = 5000
)

@Serializable
data class DataSourceDbConfigNoSecrets(
    val jdbcUrl: String = "jdbc:mysql://localhost:4306/lake",
    val username: String = "merico",
    val passwordFilePath: String,
    val driverClassName: String = "com.mysql.cj.jdbc.Driver",
    val maximumPoolSize: Int = 30,
    val minimumIdle: Int = 2,
    val idleTimeoutMs: Long = 10000,
    val connectionTimeoutMs: Long = 5000
)

fun DataSourceDbConfigNoSecrets.toDataSourceDbConfig(): DataSourceDbConfig {
    return DataSourceDbConfig(
        jdbcUrl = this.jdbcUrl,
        username = this.username,
        password = SystemFileSystem.source(Path(this.passwordFilePath)).buffered().readText(),
        driverClassName = this.driverClassName,
        maximumPoolSize = this.maximumPoolSize,
        minimumIdle = this.minimumIdle,
        idleTimeoutMs = this.idleTimeoutMs,
        connectionTimeoutMs = this.connectionTimeoutMs
    )
}

fun loadDataSourceDbConfigNoSecrets(dataSourceDbConfigPath: Path): DataSourceDbConfigNoSecrets? {
    if (!SystemFileSystem.exists(dataSourceDbConfigPath)) {
        return null
    }
    return try {
        Json.decodeFromString<DataSourceDbConfigNoSecrets>(
            DataSourceDbConfigNoSecrets.serializer(),
            SystemFileSystem.source(dataSourceDbConfigPath).buffered().readText(),
        )
    } catch (error: Exception) {
        println("Failed to load user config: $error")
        return null
    }
}

private val json = Json { encodeDefaults = true }
fun saveDataSourceDbConfigNoSecrets(dataSourceDbConfigPath: Path, config: DataSourceDbConfigNoSecrets) {
    SystemFileSystem.sink(dataSourceDbConfigPath).buffered()
        .writeText(json.encodeToString(DataSourceDbConfigNoSecrets.serializer(), config))
}


class DataSourceManagerDb(private val config: DataSourceDbConfig) : DataSourceManager {
    private var dataSourceMutex = Mutex()
    private var dataSourceDb: HikariDataSource? = null

    override fun getOrCreateDataSource(): DataSource = runBlocking {
        dataSourceMutex.withLock {
            dataSourceDb ?: run {
                val hikariConfig = HikariDataSource().apply {
                    jdbcUrl = config.jdbcUrl
                    username = config.username
                    password = config.password
                    driverClassName = config.driverClassName
                    maximumPoolSize = config.maximumPoolSize
                    minimumIdle = config.minimumIdle
                    idleTimeout = config.idleTimeoutMs
                    connectionTimeout = config.connectionTimeoutMs
                }
                HikariDataSource(hikariConfig).also { dataSourceDb = it }
            }
        }
    }

    override fun close(): Unit = runBlocking {
        dataSourceMutex.withLock {
            dataSourceDb?.use {
                it.close()
                dataSourceDb = null
            }
        }
    }
}
