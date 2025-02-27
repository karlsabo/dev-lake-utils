package com.github.karlsabo.devlake.accessor

import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import kotlin.test.Test

class PipelineAccessorTest {
    @Test
    fun testPipelineAccessor() {
        DataSourceManagerDb(loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)?.toDataSourceDbConfig()!!).use { dataSourceManager ->
            val accessor = PipelineAccessorDb(dataSourceManager.getOrCreateDataSource())
            accessor.getPipelines(1, 0).forEach {
                println(it)
            }
        }
    }
}
