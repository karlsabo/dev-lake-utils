package com.github.karlsabo.ds

import io.ktor.utils.io.core.*
import javax.sql.DataSource

interface DataSourceManager : Closeable {
    fun getOrCreateDataSource(): DataSource
}
