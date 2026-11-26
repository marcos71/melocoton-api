package ar.melocoton.students.infrastructure.persistence

import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.Student
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * Adapter of [StudentRepository]. [create] goes through a JPA entity; the rest is plain JDBC, which does
 * not see changes JPA has not flushed yet, which is why [create] flushes.
 */
@Repository
class StudentRepositoryJpa(
    private val jpa: StudentJpaRepository,
    private val jdbc: JdbcTemplate,
) : StudentRepository {

    override fun create(student: Student) {
        jpa.saveAndFlush(student.toEntity())
    }

    override fun exists(id: Long): Boolean =
        jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM student WHERE user_id = ?)", Boolean::class.java, id) ?: false

    override fun lockById(id: Long): Student? = jdbc.query(
        // Only the student row is locked: that is what serializes the bookings of one student.
        """SELECT s.user_id, s.enrollment_type, s.fixed_slot_id, s.monthly_start_date, u.active
           FROM student s JOIN app_user u ON u.id = s.user_id
           WHERE s.user_id = ? FOR UPDATE OF s""",
        { rs, _ ->
            Student(
                userId = rs.getLong("user_id"),
                enrollmentType = EnrollmentType.valueOf(rs.getString("enrollment_type")),
                fixedSlotId = rs.getLong("fixed_slot_id").takeUnless { rs.wasNull() },
                monthlyStartDate = rs.getObject("monthly_start_date", LocalDate::class.java),
                active = rs.getBoolean("active"),
            )
        },
        id,
    ).singleOrNull()

    override fun saveFixedSlot(student: Student) {
        jdbc.update("UPDATE student SET fixed_slot_id = ? WHERE user_id = ?", student.fixedSlotId, student.userId)
    }

    override fun countFixedStudents(slotId: Long, excluding: Long?): Int = jdbc.queryForObject(
        """SELECT count(*) FROM student s JOIN app_user u ON u.id = s.user_id
           WHERE s.fixed_slot_id = ? AND u.active AND s.user_id IS DISTINCT FROM ?""",
        Int::class.java, slotId, excluding,
    ) ?: 0

    override fun countFixedStudentsBySlot(slotIds: Collection<Long>): Map<Long, Int> {
        if (slotIds.isEmpty()) return emptyMap()
        val placeholders = slotIds.joinToString(",") { "?" }
        return jdbc.query(
            """SELECT s.fixed_slot_id AS slot_id, count(*) AS students FROM student s JOIN app_user u ON u.id = s.user_id
               WHERE s.fixed_slot_id IN ($placeholders) AND u.active GROUP BY s.fixed_slot_id""",
            { rs, _ -> rs.getLong("slot_id") to rs.getInt("students") },
            *slotIds.toTypedArray(),
        ).toMap()
    }

    override fun findIdsWithActiveFixedSlot(): List<Long> = jdbc.query(
        """SELECT s.user_id FROM student s
           JOIN app_user u ON u.id = s.user_id
           JOIN slot sl ON sl.id = s.fixed_slot_id
           WHERE u.active AND sl.active AND s.enrollment_type = 'MONTHLY'
           ORDER BY s.user_id""",
        { rs, _ -> rs.getLong("user_id") },
    )
}
