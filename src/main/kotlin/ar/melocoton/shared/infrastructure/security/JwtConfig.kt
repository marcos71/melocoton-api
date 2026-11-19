package ar.melocoton.shared.infrastructure.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The key that signs and checks session tokens (DI-28). The API is both the issuer and the
 * only reader, so a symmetric key (HS256) is enough.
 */
@Configuration
@EnableConfigurationProperties(SessionProperties::class)
class JwtConfig(private val properties: SessionProperties) {

    private fun key(): SecretKey = SecretKeySpec(properties.key, "HmacSHA256")

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key()))

    /** Checks the signature and the expiry against the application clock, with no extra leeway. */
    @Bean
    fun jwtDecoder(clock: Clock): JwtDecoder {
        val decoder = NimbusJwtDecoder.withSecretKey(key()).macAlgorithm(MacAlgorithm.HS256).build()
        decoder.setJwtValidator(JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) })
        return decoder
    }
}
