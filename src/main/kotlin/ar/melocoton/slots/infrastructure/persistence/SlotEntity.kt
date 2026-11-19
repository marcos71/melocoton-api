package ar.melocoton.slots.infrastructure.persistence

import ar.melocoton.slots.domain.NewSlot
import ar.melocoton.slots.domain.Slot
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.DayOfWeek
import java.time.LocalTime

/** Row of `slot` (V1). Only this adapter knows JPA (DI-04). */
@Entity
@Table(name = "slot")
class SlotEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // smallint in the schema; ISO number, 1 = Monday.
    @Column(name = "day_of_week")
    var dayOfWeek: Short,

    @Column(name = "start_time")
    var startTime: LocalTime,

    @Column(name = "end_time")
    var endTime: LocalTime,

    var capacity: Int,

    var active: Boolean,
)

fun SlotEntity.toDomain() = Slot(
    id = checkNotNull(id) { "A stored slot always has an id" },
    dayOfWeek = DayOfWeek.of(dayOfWeek.toInt()),
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    active = active,
)

fun NewSlot.toEntity() = SlotEntity(
    dayOfWeek = dayOfWeek.value.toShort(),
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    active = true,
)
