package ar.melocoton.shared.domain

import java.time.ZoneId

/**
 * The workshop's time zone. Fixed and not editable (base document, section 9; RNF-17).
 * Business rules about days and deadlines (24 hours, day 10, 23:59) are evaluated in this zone;
 * timestamps are stored in UTC (DI-14).
 */
object WorkshopTime {
    val ZONE: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")
}
