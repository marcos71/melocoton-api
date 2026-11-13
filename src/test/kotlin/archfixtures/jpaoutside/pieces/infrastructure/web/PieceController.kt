package archfixtures.jpaoutside.pieces.infrastructure.web

import jakarta.persistence.EntityManager

class PieceController(private val em: EntityManager)
