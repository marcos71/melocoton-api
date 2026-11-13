package archfixtures.good.pieces.application

import archfixtures.good.pieces.domain.Piece
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun interface PieceRepository { fun find(): Piece }

@Service
class ChangePiece(private val pieces: PieceRepository) {
    @Transactional
    fun run() = pieces.find()
}
