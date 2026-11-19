package ar.melocoton.bookings.domain

/** State of a booking (base document, section 4; docs/glosario_en.md). */
enum class BookingStatus(
    /** True when a booking in this state takes one of the lesson's places (RN-02). */
    val occupiesCapacity: Boolean,
) {
    SCHEDULED(occupiesCapacity = true),
    PRESENT(occupiesCapacity = true),
    ABSENT(occupiesCapacity = false),
    ABSENT_RECOVERABLE(occupiesCapacity = false),
    MADE_UP(occupiesCapacity = false),
    CANCELLED(occupiesCapacity = false),
    NOT_RECOVERABLE(occupiesCapacity = false),
    UNRECORDED(occupiesCapacity = false),
    ;

    companion object {
        /** The states that take a place; persistence filters by this set instead of repeating it in SQL. */
        val OCCUPYING: Set<BookingStatus> = entries.filter { it.occupiesCapacity }.toSet()
    }
}

/** How a booking was made (docs/glosario_en.md). */
enum class BookingType { FIXED, DROP_IN, MAKEUP }

/** Why a booking was cancelled (docs/glosario_en.md). */
enum class CancellationReason { STUDENT, TEACHER, WORKSHOP_CLOSURE, WITHDRAWAL, ENROLLMENT_CHANGE, SLOT_DEACTIVATED }
