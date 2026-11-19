package ar.melocoton.students.infrastructure.persistence

import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.FixedSlotSummary
import ar.melocoton.students.domain.Student
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentProfile
import ar.melocoton.students.domain.StudentStatusFilter
import ar.melocoton.students.domain.StudentSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Adapter of [StudentRepository]. The write goes through a JPA entity; the reads are one query that joins
 * `app_user`, `student` and `slot` (DI-21: a composed screen read lives in the module that shows it), so
 * it needs neither the auth nor the slots entities. Plain JDBC does not see changes JPA has not flushed
 * yet, which is why [create] flushes.
 */
@Repository
class StudentRepositoryJpa(
    private val jpa: StudentJpaRepository,
    private val jdbc: JdbcTemplate,
) : StudentRepository {

    override fun create(student: Student) {
        jpa.saveAndFlush(student.toEntity())
    }

    override fun findProfile(id: Long): StudentProfile? =
        jdbc.query("$PROFILE_SELECT WHERE u.id = ?", PROFILE_ROW, id).singleOrNull()

    override fun findAll(filter: StudentFilter): List<StudentSummary> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        when (filter.status) {
            StudentStatusFilter.ACTIVE -> conditions += "u.active"
            StudentStatusFilter.INACTIVE -> conditions += "NOT u.active"
            StudentStatusFilter.ALL -> Unit
        }
        filter.enrollmentType?.let {
            conditions += "s.enrollment_type = ?"
            args += it.name
        }
        filter.slotId?.let {
            conditions += "s.fixed_slot_id = ?"
            args += it
        }
        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
        return jdbc.query("$PROFILE_SELECT $where ORDER BY lower(u.name), u.id", SUMMARY_ROW, *args.toTypedArray())
    }

    private companion object {
        const val PROFILE_SELECT = """
            SELECT u.id, u.name, u.email, u.phone, u.active, s.enrollment_type, s.monthly_start_date,
                   sl.id AS slot_id, sl.day_of_week, sl.start_time, sl.end_time
            FROM student s
            JOIN app_user u ON u.id = s.user_id
            LEFT JOIN slot sl ON sl.id = s.fixed_slot_id"""

        val PROFILE_ROW = RowMapper { rs, _ ->
            StudentProfile(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                email = rs.getString("email"),
                phone = rs.getString("phone"),
                active = rs.getBoolean("active"),
                enrollmentType = EnrollmentType.valueOf(rs.getString("enrollment_type")),
                monthlyStartDate = rs.getObject("monthly_start_date", LocalDate::class.java),
                fixedSlot = rs.fixedSlot(),
            )
        }

        val SUMMARY_ROW = RowMapper { rs, _ ->
            StudentSummary(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                enrollmentType = EnrollmentType.valueOf(rs.getString("enrollment_type")),
                fixedSlot = rs.fixedSlot(),
                active = rs.getBoolean("active"),
            )
        }

        /** The joined slot, or null when the student has none (the LEFT JOIN leaves its columns null). */
        fun ResultSet.fixedSlot(): FixedSlotSummary? {
            val slotId = getLong("slot_id")
            if (wasNull()) return null
            return FixedSlotSummary(
                id = slotId,
                dayOfWeek = DayOfWeek.of(getInt("day_of_week")),
                startTime = getObject("start_time", LocalTime::class.java),
                endTime = getObject("end_time", LocalTime::class.java),
            )
        }
    }
}
