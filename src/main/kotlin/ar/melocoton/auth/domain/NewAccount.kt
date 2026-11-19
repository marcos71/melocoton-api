package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.Role
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * An account that does not exist yet, so it has no id. It starts active and without a password:
 * the person defines it from the invitation link (D-11).
 */
class NewAccount private constructor(
    val name: String,
    val email: String,
    val phone: String,
    val role: Role,
    val sessionsValidFrom: Instant,
) {
    val passwordHash: String? = null
    val active: Boolean = true

    companion object {
        /** Invited assistant teacher (CU-02 D1, RF-53). The text fields are trimmed (DI-34). */
        fun assistantTeacher(name: String, email: String, phone: String, now: Instant) =
            invited(name, email, phone, Role.ASSISTANT_TEACHER, now)

        /** Invited student (CU-02, RF-04, DI-46): same rules as an assistant teacher, with the student role. */
        fun student(name: String, email: String, phone: String, now: Instant) =
            invited(name, email, phone, Role.STUDENT, now)

        private fun invited(name: String, email: String, phone: String, role: Role, now: Instant) = NewAccount(
            name = name.trim(),
            email = email.trim(),
            phone = phone.trim(),
            role = role,
            // PostgreSQL keeps microseconds; truncating makes the stored value equal to this one.
            sessionsValidFrom = now.truncatedTo(ChronoUnit.MICROS),
        )
    }
}
