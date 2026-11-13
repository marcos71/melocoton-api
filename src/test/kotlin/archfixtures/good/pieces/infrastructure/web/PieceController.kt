package archfixtures.good.pieces.infrastructure.web

import archfixtures.good.pieces.application.ChangePiece
import org.springframework.web.bind.annotation.RestController

@RestController
class PieceController(private val changePiece: ChangePiece)
