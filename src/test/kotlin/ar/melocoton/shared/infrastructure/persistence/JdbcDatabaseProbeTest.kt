package ar.melocoton.shared.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

@SpringBootTest
class JdbcDatabaseProbeTest(@Autowired private val jdbc: JdbcTemplate) {

    @Test
    fun `the test database is reachable`() {
        assertThat(JdbcDatabaseProbe(jdbc).isReachable()).isTrue()
    }

    @Test
    fun `a database nobody listens on is reported as unreachable, not as an exception`() {
        val nowhere = DriverManagerDataSource("jdbc:postgresql://localhost:1/nowhere?connectTimeout=2")

        assertThat(JdbcDatabaseProbe(JdbcTemplate(nowhere)).isReachable()).isFalse()
    }
}
