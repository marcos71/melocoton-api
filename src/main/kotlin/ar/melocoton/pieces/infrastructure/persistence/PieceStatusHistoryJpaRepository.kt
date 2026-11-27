package ar.melocoton.pieces.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface PieceStatusHistoryJpaRepository : JpaRepository<PieceStatusHistoryEntity, Long>
