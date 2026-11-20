package ar.melocoton.slots.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

class SlotBookingRuleTest {

    private fun slot(active: Boolean) = Slot(1, DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(20, 0), 8, active)

    @Test
    fun `RF08_anActiveSlotAcceptsBookings`() {
        assertThatCode { slot(active = true).requireAcceptsBookings() }.doesNotThrowAnyException()
    }

    @Test
    fun `RF09_aDeactivatedSlotDoesNotAcceptBookings`() {
        assertThatThrownBy { slot(active = false).requireAcceptsBookings() }
            .isInstanceOfSatisfying(SlotNotAcceptingBookings::class.java) {
                assertThat(it.code).isEqualTo("slot_inactive")
                assertThat(it.message).isEqualTo("El turno está desactivado: no admite reservas nuevas.")
            }
    }
}
