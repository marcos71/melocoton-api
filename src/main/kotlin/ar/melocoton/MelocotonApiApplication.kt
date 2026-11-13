package ar.melocoton

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MelocotonApiApplication

fun main(args: Array<String>) {
	runApplication<MelocotonApiApplication>(*args)
}
