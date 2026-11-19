package ar.melocoton.bookings.infrastructure.persistence

import ar.melocoton.bookings.domain.BookingStatus
import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.bookings.domain.CancellationReason
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/** Row of `booking` (V1). Only this adapter knows JPA (DI-04). */
@Entity
@Table(name = "booking")
class BookingEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "student_id")
    var studentId: Long,

    @Column(name = "slot_id")
    var slotId: Long,

    @Column(name = "lesson_date")
    var lessonDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var type: BookingType,

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var status: BookingStatus,

    @Column(name = "original_booking_id")
    var originalBookingId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", columnDefinition = "text")
    var cancellationReason: CancellationReason?,

    @Column(name = "attendance_recorded_by")
    var attendanceRecordedBy: Long?,

    @Column(name = "attendance_recorded_at")
    var attendanceRecordedAt: Instant?,
)
