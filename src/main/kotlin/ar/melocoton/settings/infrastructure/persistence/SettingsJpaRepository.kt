package ar.melocoton.settings.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SettingsJpaRepository : JpaRepository<SettingsEntity, Short>
