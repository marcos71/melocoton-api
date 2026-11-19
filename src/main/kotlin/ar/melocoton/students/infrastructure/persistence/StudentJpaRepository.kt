package ar.melocoton.students.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface StudentJpaRepository : JpaRepository<StudentEntity, Long>
