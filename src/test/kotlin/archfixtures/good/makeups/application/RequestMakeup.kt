package archfixtures.good.makeups.application

import archfixtures.good.pieces.application.port.PieceRepository
import archfixtures.good.pieces.domain.Piece
import org.springframework.stereotype.Service

// A use case may use another module's ports and domain objects (DI-21).
@Service
class RequestMakeup(private val pieces: PieceRepository) {
    fun run(): Piece = pieces.find()
}
