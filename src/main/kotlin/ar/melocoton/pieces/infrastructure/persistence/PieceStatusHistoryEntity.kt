package ar.melocoton.pieces.infrastructure.persistence

import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceHistoryEvent
import ar.melocoton.pieces.domain.PieceStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.util.UUID

/** Row of `piece_status_history` (V1): insert-only (RN-05), so Hibernate never issues an UPDATE for it. */
@Entity
@Immutable
@Table(name = "piece_status_history")
class PieceStatusHistoryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "piece_id")
    val pieceId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", columnDefinition = "text")
    val previousStatus: PieceStatus?,

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", columnDefinition = "text")
    val newStatus: PieceStatus?,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", columnDefinition = "text")
    val eventType: PieceEventType,

    @Column(name = "occurred_at")
    val occurredAt: Instant,

    @Column(name = "user_id")
    val userId: Long,

    @Column(name = "operation_id")
    val operationId: UUID,
)

fun PieceHistoryEvent.toEntity() = PieceStatusHistoryEntity(
    pieceId = pieceId,
    previousStatus = previousStatus,
    newStatus = newStatus,
    eventType = eventType,
    occurredAt = occurredAt,
    userId = userId,
    operationId = operationId,
)
