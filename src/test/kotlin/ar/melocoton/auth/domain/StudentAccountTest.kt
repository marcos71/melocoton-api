package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.Role
import ar.melocoton.shared.domain.PersonName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** The account side of registering and editing a student (CU-02, RF-04, DI-46). */
class StudentAccountTest {

    private val now = Instant.parse("2026-11-16T13:00:00.123456789Z")

    private fun account() = UserAccount(
        id = 1, name = "Lucía", email = "lucia@test.local", phone = "1100000000", role = Role.STUDENT,
        passwordHash = null, active = true, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = now,
    )

    @Test
    fun `RF04_aNewStudentAccountIsActiveWithoutPasswordAndTrimmed`() {
        val new = NewAccount.student(" Lucía ", " lucia@test.local ", " 1155550000 ", now)

        assertThat(new.role).isEqualTo(Role.STUDENT)
        assertThat(new.name).isEqualTo("Lucía")
        assertThat(new.email).isEqualTo("lucia@test.local")
        assertThat(new.phone).isEqualTo("1155550000")
        assertThat(new.passwordHash).isNull()
        assertThat(new.active).isTrue()
    }

    @Test
    fun `RF04_changingTheContactReplacesOnlyWhatIsGiven`() {
        val account = account()

        account.changeContact(name = "Lucía G.", phone = null)
        assertThat(account.name).isEqualTo("Lucía G.")
        assertThat(account.phone).isEqualTo("1100000000")

        account.changeContact(name = null, phone = "1166667777")
        assertThat(account.name).isEqualTo("Lucía G.")
        assertThat(account.phone).isEqualTo("1166667777")
        assertThat(account.email).isEqualTo("lucia@test.local")
    }

    @Test
    fun `RF04_anAccountCannotBeBuiltWithABlankOrTooLongNameOrABlankPhone`() {
        val max = "a".repeat(PersonName.MAX_LENGTH)

        assertThat(NewAccount.student(max, "a@test.local", "1", now).name).isEqualTo(max)
        assertThatThrownBy { NewAccount.student("  ", "a@test.local", "1", now) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NewAccount.student(max + "a", "a@test.local", "1", now) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NewAccount.student("Lucía", "a@test.local", " ", now) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NewAccount.assistantTeacher("", "a@test.local", "1", now) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `DI46_changingTheContactTrimsAndRejectsBlankOrTooLongValuesWithoutChangingAnything`() {
        val account = account()
        val max = "a".repeat(PersonName.MAX_LENGTH)

        account.changeContact(name = " $max ", phone = " 1166667777 ")
        assertThat(account.name).isEqualTo(max)
        assertThat(account.phone).isEqualTo("1166667777")

        assertThatThrownBy { account.changeContact(name = " ", phone = null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { account.changeContact(name = max + "a", phone = null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { account.changeContact(name = "Otra", phone = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(account.name).isEqualTo(max)
        assertThat(account.phone).isEqualTo("1166667777")
    }
}
