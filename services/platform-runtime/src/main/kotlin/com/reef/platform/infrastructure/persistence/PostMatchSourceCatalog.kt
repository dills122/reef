package com.reef.platform.infrastructure.persistence

import javax.sql.DataSource

/** Durable source identity from canonical runtime storage. */
class PostMatchSourceCatalog(private val sourceDataSource: DataSource) {
    fun generation(): String = sourceDataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT generation::text FROM runtime.postmatch_source_generation WHERE singleton = TRUE"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "canonical post-match source generation is missing" }
                rows.getString(1)
            }
        }
    }

}
