package ar.melocoton.students.infrastructure.persistence

import ar.melocoton.bookings.domain.BookingStatus
import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.students.application.port.FixedSlotSummary
import ar.melocoton.students.application.port.StudentDirectoryEntry
import ar.melocoton.students.application.port.StudentProfile
import ar.melocoton.students.application.port.StudentQueries
import ar.melocoton.students.application.port.StudentSummary
import ar.melocoton.students.application.port.UpcomingLesson
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentStatusFilter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Adapter of [StudentQueries]. The reads join `app_user`, `student`, `slot` and `booking` (DI-21: a
 * composed screen read lives in the module that shows it), so it needs neither the auth, slots nor
 * bookings entities. It reads the bookings and slots tables read-only: that is coupling to their schema,
 * accepted for composed reads and covered by the integration tests (DI-60).
 */
@Repository
class StudentQueriesJdbc(private val jdbc: JdbcTemplate) : StudentQueries {

    override fun findProfile(id: Long): StudentProfile? =
        jdbc.query("$PROFILE_SELECT WHERE u.id = ?", PROFILE_ROW, id).singleOrNull()

    override fun findAll(filter: StudentFilter): List<StudentSummary> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        statusCondition(filter.status)?.let { conditions += it }
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

    override fun findDirectory(status: StudentStatusFilter): List<StudentDirectoryEntry> {
        val where = statusCondition(status)?.let { "WHERE $it" }.orEmpty()
        return jdbc.query(
            "SELECT u.id, u.name, u.phone, u.active FROM student s JOIN app_user u ON u.id = s.user_id $where ORDER BY lower(u.name), u.id",
            DIRECTORY_ROW,
        )
    }

    override fun findUpcomingLessons(id: Long, fromDate: LocalDate): List<UpcomingLesson> = jdbc.query(
        """SELECT b.id, b.lesson_date, b.type, sl.id AS slot_id, sl.day_of_week, sl.start_time, sl.end_time
           FROM booking b JOIN slot sl ON sl.id = b.slot_id
           WHERE b.student_id = ? AND b.status = ? AND b.lesson_date >= ?
           ORDER BY b.lesson_date, sl.start_time, b.id""",
        { rs, _ ->
            UpcomingLesson(
                bookingId = rs.getLong("id"),
                lessonDate = rs.getObject("lesson_date", LocalDate::class.java),
                slotId = rs.getLong("slot_id"),
                dayOfWeek = DayOfWeek.of(rs.getInt("day_of_week")),
                startTime = rs.getObject("start_time", LocalTime::class.java),
                endTime = rs.getObject("end_time", LocalTime::class.java),
                type = BookingType.valueOf(rs.getString("type")),
            )
        },
        id, BookingStatus.SCHEDULED.name, fromDate,
    )

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

        val DIRECTORY_ROW = RowMapper { rs, _ ->
            StudentDirectoryEntry(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                active = rs.getBoolean("active"),
            )
        }

        /** The SQL condition on the account's state, or null when every state is wanted. */
        fun statusCondition(status: StudentStatusFilter): String? = when (status) {
            StudentStatusFilter.ACTIVE -> "u.active"
            StudentStatusFilter.INACTIVE -> "NOT u.active"
            StudentStatusFilter.ALL -> null
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
