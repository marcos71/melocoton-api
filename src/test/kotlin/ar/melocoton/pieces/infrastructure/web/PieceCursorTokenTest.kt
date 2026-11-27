package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.pieces.application.port.PieceCursor
import ar.melocoton.pieces.domain.InvalidPiece
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Base64

/** The opaque cursor and the page size of the piece lists (DI-87), without Spring. */
class PieceCursorTokenTest {

    private fun encode(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

    @Test
    fun `DI87_aCursorRoundTripsWithMicrosecondPrecision`() {
        val cursor = PieceCursor(Instant.parse("2026-11-26T15:00:00.123456Z"), 42)

        assertThat(cursor.toToken().toPieceCursor()).isEqualTo(cursor)
    }

    @ParameterizedTest
    @ValueSource(strings = ["garbage!", "", "eA", "MjAyNi0wMS0wMVQwMDowMDowMFo", "bm90QURhdGV8NQ", "MjAyNi0wMS0wMVQwMDowMDowMFp8YWJj", "MjAyNi0wMS0wMVQwMDowMDowMFp8MA"])
    fun `DI87_aMalformedCursorIsAValidationError`(token: String) {
        assertThatThrownBy { token.toPieceCursor() }.isInstanceOf(InvalidPiece::class.java)
    }

    @Test
    fun `DI87_aCursorWithANonPositiveIdIsAValidationError`() {
        assertThatThrownBy { encode("2026-01-01T00:00:00Z|-3").toPieceCursor() }.isInstanceOf(InvalidPiece::class.java)
    }

    @Test
    fun `DI87_pageSizeDefaultsTo50AndAcceptsOneTo100`() {
        assertThat(null.toPageSize()).isEqualTo(50)
        assertThat(1.toPageSize()).isEqualTo(1)
        assertThat(100.toPageSize()).isEqualTo(100)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 101])
    fun `DI87_pageSizeOutsideOneTo100IsAValidationError`(size: Int) {
        assertThatThrownBy { size.toPageSize() }.isInstanceOf(InvalidPiece::class.java)
    }
}
