package ar.melocoton.pieces.infrastructure.persistence

import ar.melocoton.pieces.domain.NewPiece
import ar.melocoton.pieces.domain.PieceStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Row of `piece` (V1). Only this adapter knows JPA (DI-04). It is only inserted here: every later write is a guarded UPDATE (D-34). */
@Entity
@Table(name = "piece")
class PieceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "student_id")
    val studentId: Long,

    val name: String,

    val notes: String?,

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    val status: PieceStatus,

    @Column(name = "created_at")
    val createdAt: Instant,

    @Column(name = "voided_at")
    val voidedAt: Instant? = null,

    @Column(name = "voided_by")
    val voidedBy: Long? = null,

    @Column(name = "void_reason")
    val voidReason: String? = null,
)

fun NewPiece.toEntity(createdAt: Instant) = PieceEntity(
    studentId = studentId,
    name = text.name,
    notes = text.notes,
    status = PieceStatus.IN_PROGRESS,
    createdAt = createdAt,
)
