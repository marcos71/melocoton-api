package ar.melocoton.settings.application.port

import ar.melocoton.settings.domain.Settings

/** Output port for the workshop parameters (single row). Read-only for now. */
interface SettingsRepository {

    /** The current parameters; the row always exists (V1 inserts it). */
    fun get(): Settings
}
