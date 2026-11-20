package ar.melocoton.settings.infrastructure.persistence

import ar.melocoton.settings.domain.Settings
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/** The single row of `settings` (V1). Read-only here: changing a parameter comes with CU-20. */
@Entity
@Immutable
@Table(name = "settings")
class SettingsEntity(
    @Id
    val id: Short,

    @Column(name = "makeup_horizon_days")
    val makeupHorizonDays: Short,
)

fun SettingsEntity.toDomain() = Settings(makeupHorizonDays = makeupHorizonDays.toInt())
