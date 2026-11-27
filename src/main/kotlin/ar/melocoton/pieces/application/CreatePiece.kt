package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.NewPiece
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * CU-12, RF-24, RF-25, RF-28: a student or a teacher creates a piece for a student. The student row is locked
 * first, which serializes the names of that student (DI-73), and the creation event is written in the same transaction.
 */
@Service
class CreatePiece(
    private val students: StudentRepository,
    private val pieces: PieceRepository,
    private val queries: PieceQueries,
    private val clock: Clock,
) {
    /** @param actorId who creates it: the student themselves, or the teacher */
    @Transactional
    fun create(studentId: Long, name: String, notes: String?, actorId: Long): PieceDetail {
        val now = clock.instant()
        val student = students.lockById(studentId) ?: throw StudentNotFound()
        student.requireActive()
        val newPiece = NewPiece.create(studentId, name, notes, pieces.namesInUse(studentId))
        val id = pieces.create(newPiece, now)
        pieces.appendHistory(listOf(newPiece.createdEvent(id, actorId, now, UUID.randomUUID())))
        return checkNotNull(queries.findDetail(id)) { "Piece $id was just created" }
    }
}
