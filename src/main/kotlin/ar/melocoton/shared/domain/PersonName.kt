package ar.melocoton.shared.domain

/** Rules of a person's name, shared by the accounts and the students (a name is one column of app_user). */
object PersonName {
    /** Most characters a name may have, after trimming. */
    const val MAX_LENGTH = 80
}
