package archfixtures.crossinfra.makeups.infrastructure.web

import archfixtures.crossinfra.payments.infrastructure.persistence.PaymentJpaRepository

class MakeupController(private val payments: PaymentJpaRepository)
