package ar.melocoton.settings.infrastructure.persistence

import ar.melocoton.settings.application.port.SettingsRepository
import ar.melocoton.settings.domain.Settings
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

/** Adapter of [SettingsRepository] over Spring Data JPA. */
@Repository
class SettingsRepositoryJpa(private val jpa: SettingsJpaRepository) : SettingsRepository {

    override fun get(): Settings =
        checkNotNull(jpa.findByIdOrNull(SINGLE_ROW_ID)) { "The settings row is missing" }.toDomain()

    private companion object {
        const val SINGLE_ROW_ID: Short = 1
    }
}
