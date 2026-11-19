package ar.melocoton.bookings.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OccupancyTest {

    @Test
    fun `CP02_occupancyCountsScheduledAndPresentOnly`() {
        val statuses = List(6) { BookingStatus.SCHEDULED } +
            List(2) { BookingStatus.PRESENT } +
            BookingStatus.CANCELLED +
            BookingStatus.ABSENT

        assertThat(occupancyOf(statuses)).isEqualTo(8)
    }

    @Test
    fun `RN02_onlyScheduledAndPresentOccupyCapacity`() {
        assertThat(BookingStatus.OCCUPYING).containsExactlyInAnyOrder(BookingStatus.SCHEDULED, BookingStatus.PRESENT)
        assertThat(BookingStatus.entries.filter { !it.occupiesCapacity }).containsExactlyInAnyOrder(
            BookingStatus.ABSENT,
            BookingStatus.ABSENT_RECOVERABLE,
            BookingStatus.MADE_UP,
            BookingStatus.CANCELLED,
            BookingStatus.NOT_RECOVERABLE,
            BookingStatus.UNRECORDED,
        )
    }

    @Test
    fun `RN02_aLessonWithNoBookingsHasNoOccupancy`() {
        assertThat(occupancyOf(emptyList())).isZero()
    }
}
