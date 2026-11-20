package ar.melocoton.students.application

import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentProfile
import ar.melocoton.students.domain.notStarted
import java.time.LocalDateTime

/**
 * A student's profile with the scheduled classes that have not started (DI-46). The profile and the
 * bookings are two reads; which lessons count as upcoming is decided by the domain.
 */
internal fun StudentRepository.findProfileWithLessons(id: Long, now: LocalDateTime): StudentProfile? =
    findProfile(id)?.copy(upcomingLessons = findUpcomingLessons(id, now.toLocalDate()).notStarted(now))
