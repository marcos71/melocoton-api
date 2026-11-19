package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.domain.UserAccount
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Body of POST /api/v1/assistant-teachers. All three are required (DI-34). The text is trimmed
 * here, before bean validation, so padded values pass and a whitespace-only one counts as blank.
 */
class CreateAssistantTeacherRequest(name: String, email: String, phone: String) {
    @field:NotBlank
    val name: String = name.trim()

    // @Email alone accepts "a@b"; the pattern asks for a dot in the domain, as a person would write it.
    @field:NotBlank
    @field:Email
    @field:Pattern(regexp = EMAIL_WITH_DOMAIN_DOT)
    val email: String = email.trim()

    @field:NotBlank
    val phone: String = phone.trim()
}

private const val EMAIL_WITH_DOMAIN_DOT = "^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$"

/** An assistant teacher's account. */
data class AssistantTeacherResponse(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String,
    val active: Boolean,
)

fun UserAccount.toAssistantTeacherResponse() =
    AssistantTeacherResponse(id = id, name = name, email = email, phone = phone, active = active)
