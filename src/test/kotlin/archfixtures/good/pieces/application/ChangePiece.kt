package archfixtures.good.pieces.application

import archfixtures.good.pieces.application.port.PieceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChangePiece(private val pieces: PieceRepository) {
    @Transactional
    fun run() = pieces.find()
}
