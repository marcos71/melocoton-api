package ar.melocoton.shared.application

import ar.melocoton.shared.application.port.DatabaseProbe
import org.springframework.stereotype.Service

enum class ComponentStatus { UP, DOWN }

data class HealthReport(val database: ComponentStatus) {
    /** The API is up only when every component it depends on is up. */
    val status: ComponentStatus
        get() = if (database == ComponentStatus.UP) ComponentStatus.UP else ComponentStatus.DOWN
}

/** Use case: report whether the API and its database are working. */
@Service
class CheckHealth(private val database: DatabaseProbe) {

    fun check() = HealthReport(
        database = if (database.isReachable()) ComponentStatus.UP else ComponentStatus.DOWN,
    )
}
