package ar.melocoton.settings.domain

/** The editable parameters of the workshop (CU-20). Only the ones a use case needs so far. */
data class Settings(
    /** How many days ahead a make-up can be requested; it also sets the end of the rolling window (DI-42). */
    val makeupHorizonDays: Int,
)
