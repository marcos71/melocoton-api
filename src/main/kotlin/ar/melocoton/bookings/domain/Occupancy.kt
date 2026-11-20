package ar.melocoton.bookings.domain

/**
 * Occupied capacity of a lesson (RN-02): how many of its bookings take a place, whatever their
 * type (fixed, drop-in or make-up). Only SCHEDULED and PRESENT count.
 */
fun occupancyOf(bookingStatuses: Collection<BookingStatus>): Int = bookingStatuses.count { it.occupiesCapacity }

/**
 * A booking that is not a fixed one (drop-in, make-up) needs a free place; fixed bookings may exceed the
 * capacity and the lesson is then overbooked (RF-11, DI-43).
 *
 * @throws CapacityFull every place of the lesson is occupied
 */
fun requireFreePlace(capacity: Int, occupancy: Int) {
    if (occupancy >= capacity) throw CapacityFull()
}
