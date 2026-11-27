package ar.melocoton.pieces.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface PieceJpaRepository : JpaRepository<PieceEntity, Long>
