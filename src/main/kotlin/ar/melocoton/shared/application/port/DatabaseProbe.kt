package ar.melocoton.shared.application.port

/** Output port: tells whether the database answers. */
fun interface DatabaseProbe {
    fun isReachable(): Boolean
}
