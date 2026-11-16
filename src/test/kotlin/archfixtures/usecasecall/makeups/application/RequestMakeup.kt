package archfixtures.usecasecall.makeups.application

import archfixtures.usecasecall.pieces.application.ChangePiece
import org.springframework.stereotype.Service

// Breaks DI-21: a use case injects another use case instead of its repository.
@Service
class RequestMakeup(private val changePiece: ChangePiece)
