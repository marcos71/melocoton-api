package archfixtures.good.pieces.infrastructure.persistence

import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
class PieceEntity(@Id val id: Long)
