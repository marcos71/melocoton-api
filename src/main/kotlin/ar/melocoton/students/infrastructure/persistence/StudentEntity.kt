package ar.melocoton.students.infrastructure.persistence

import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.Student
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Persistable
import java.time.LocalDate

/**
 * Row of `student` (V1). Only this adapter knows JPA (DI-04). The id is the account's, assigned by the
 * caller, so Spring Data cannot tell a new row from a stored one by looking at it: this entity is only
 * ever inserted here (updates come with later iterations), so it always says it is new and is persisted
 * directly instead of being looked up first.
 */
@Entity
@Table(name = "student")
class StudentEntity(
    @Id
    @Column(name = "user_id")
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "enrollment_type", columnDefinition = "text")
    val enrollmentType: EnrollmentType,

    @Column(name = "fixed_slot_id")
    val fixedSlotId: Long?,

    @Column(name = "monthly_start_date")
    val monthlyStartDate: LocalDate?,
) : Persistable<Long> {
    override fun getId(): Long = userId

    override fun isNew(): Boolean = true
}

fun Student.toEntity() = StudentEntity(
    userId = userId,
    enrollmentType = enrollmentType,
    fixedSlotId = fixedSlotId,
    monthlyStartDate = monthlyStartDate,
)
