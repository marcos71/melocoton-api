package ar.melocoton

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Recreates the test database on every test run: drops everything and
 * applies all migrations from scratch, so no test depends on leftovers (DI-19).
 */
@Configuration
@Profile("test")
class CleanTestDatabaseConfig {

    @Bean
    fun cleanThenMigrate() = FlywayMigrationStrategy { flyway ->
        // Spring hands Flyway a DataSource (not a URL) when it uses its own role,
        // so the database name is read from a live connection.
        val database = flyway.configuration.dataSource.connection.use { it.catalog }
        requireTestDatabase(database)
        flyway.clean()
        flyway.migrate()
    }
}

/** Safety net: never wipe a database that is not a test database. */
fun requireTestDatabase(databaseName: String?) {
    check(databaseName != null && databaseName.endsWith("_test")) {
        "Refusing to clean a non-test database: $databaseName"
    }
}
