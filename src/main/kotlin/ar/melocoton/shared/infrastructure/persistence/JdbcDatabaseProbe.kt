package ar.melocoton.shared.infrastructure.persistence

import ar.melocoton.shared.application.port.DatabaseProbe
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Adapter for [DatabaseProbe]: runs a trivial query against PostgreSQL. */
@Component
class JdbcDatabaseProbe(private val jdbc: JdbcTemplate) : DatabaseProbe {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isReachable(): Boolean = try {
        jdbc.queryForObject("SELECT 1", Int::class.java) == 1
    } catch (e: Exception) {
        log.warn("Database is not reachable: {}", e.message)
        false
    }
}
