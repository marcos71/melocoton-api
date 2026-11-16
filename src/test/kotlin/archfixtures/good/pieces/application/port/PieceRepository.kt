package archfixtures.good.pieces.application.port

import archfixtures.good.pieces.domain.Piece

fun interface PieceRepository { fun find(): Piece }
