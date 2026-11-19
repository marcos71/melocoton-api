package ar.melocoton.bookings.domain

/**
 * Occupied capacity of a lesson (RN-02): how many of its bookings take a place, whatever their
 * type (fixed, drop-in or make-up). Only SCHEDULED and PRESENT count.
 */
fun occupancyOf(bookingStatuses: Collection<BookingStatus>): Int = bookingStatuses.count { it.occupiesCapacity }
