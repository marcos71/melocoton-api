package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.BulkAdvance
import ar.melocoton.pieces.domain.BulkItem
import ar.melocoton.pieces.domain.PieceStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** What a bulk change did: the operation that groups its events, and its size. */
data class BulkChangeResult(val operationId: UUID, val newStatus: PieceStatus, val count: Int, val studentCount: Int)

/**
 * CU-14, RF-29, RF-30, RF-61, D-06, D-34: a teacher advances the pieces of a kiln load together. It is all or nothing:
 * the single guarded UPDATE changes every piece or the transaction rolls back, and history is written only after
 * every piece was changed. A piece already voided or changed is reported with the others, never silently skipped.
 */
@Service
class BulkChangePieceStatus(
    private val pieces: PieceRepository,
    private val clock: Clock,
) {
    @Transactional
    fun advance(items: List<BulkItem>, actorId: Long): BulkChangeResult {
        val plan = BulkAdvance.plan(items, pieces.findByIds(items.map { it.pieceId }))
        val updated = pieces.advanceAll(plan.pieceIds, plan.from, plan.to)
        if (updated.size != plan.pieceIds.size) {
            // Someone changed them after the read. The exception rolls back the rows this UPDATE did change.
            throw plan.conflictAfterRace(pieces.findByIds(plan.pieceIds - updated))
        }
        val operationId = UUID.randomUUID()
        pieces.appendHistory(plan.events(actorId, clock.instant(), operationId))
        return BulkChangeResult(operationId, plan.to, plan.pieceIds.size, plan.studentCount)
    }
}
