package ar.melocoton.bookings.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A lesson has started once its date and the slot's start time are not after [now] (workshop zone). */
fun lessonHasStarted(lessonDate: LocalDate, slotStartTime: LocalTime, now: LocalDateTime): Boolean =
    !LocalDateTime.of(lessonDate, slotStartTime).isAfter(now)

/** The first date, from today on, whose lesson of a slot starting at [slotStartTime] has not started. */
fun firstUnstartedLessonDate(slotStartTime: LocalTime, now: LocalDateTime): LocalDate =
    if (slotStartTime > now.toLocalTime()) now.toLocalDate() else now.toLocalDate().plusDays(1)
