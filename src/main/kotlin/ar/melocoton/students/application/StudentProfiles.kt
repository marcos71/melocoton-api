package ar.melocoton.students.application

import ar.melocoton.bookings.domain.lessonHasStarted
import ar.melocoton.students.application.port.StudentProfile
import ar.melocoton.students.application.port.StudentQueries
import java.time.LocalDateTime

/**
 * A student's profile with the scheduled classes that have not started (DI-46). The profile and the
 * bookings are two reads; whether a lesson has started is decided by the bookings domain. The order of
 * the lessons is the one the adapter gives (date, start time, id).
 */
internal fun StudentQueries.findProfileWithLessons(id: Long, now: LocalDateTime): StudentProfile? =
    findProfile(id)?.let { profile ->
        profile.copy(
            upcomingLessons = findUpcomingLessons(id, now.toLocalDate())
                .filterNot { lessonHasStarted(it.lessonDate, it.startTime, now) },
        )
    }
