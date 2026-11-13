package ar.melocoton.shared.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CheckHealthTest {

    @Test
    fun `everything is up when the database answers`() {
        val report = CheckHealth { true }.check()

        assertThat(report.database).isEqualTo(ComponentStatus.UP)
        assertThat(report.status).isEqualTo(ComponentStatus.UP)
    }

    @Test
    fun `the API is down when the database does not answer`() {
        val report = CheckHealth { false }.check()

        assertThat(report.database).isEqualTo(ComponentStatus.DOWN)
        assertThat(report.status).isEqualTo(ComponentStatus.DOWN)
    }
}
