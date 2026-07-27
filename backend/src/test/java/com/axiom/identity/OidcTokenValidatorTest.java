package com.axiom.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcTokenValidatorTest {
    private final OidcTokenValidator validator = new OidcTokenValidator();

    @Test void verifiesSignatureIssuerAudienceExpiryAndNonce() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("key-1").generate();
        String token = token(key, "nonce-1", "axiom-client", Instant.now().plusSeconds(300));
        var claims = validator.validate(token, new JWKSet(key.toPublicJWK()).toString(),
                "https://issuer.example", "axiom-client", "nonce-1", Duration.ofSeconds(30));
        assertThat(claims).containsEntry("email", "operator@example.com").containsEntry("sub", "subject-42");
    }

    @Test void refusesNonceReplayEvenWhenSignatureIsValid() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("key-1").generate();
        assertThatThrownBy(() -> validator.validate(
                token(key, "old-nonce", "axiom-client", Instant.now().plusSeconds(300)),
                new JWKSet(key.toPublicJWK()).toString(), "https://issuer.example",
                "axiom-client", "current-nonce", Duration.ZERO))
                .hasMessageContaining("nonce mismatch");
    }

    @Test void refusesTokenSignedByAKeyOutsideProviderJwks() throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("key-1").generate();
        RSAKey provider = new RSAKeyGenerator(2048).keyID("key-1").generate();
        assertThatThrownBy(() -> validator.validate(
                token(attacker, "nonce", "axiom-client", Instant.now().plusSeconds(300)),
                new JWKSet(provider.toPublicJWK()).toString(), "https://issuer.example",
                "axiom-client", "nonce", Duration.ZERO))
                .hasMessageContaining("signature");
    }

    private static String token(RSAKey key, String nonce, String audience, Instant expiry) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("https://issuer.example").subject("subject-42")
                        .audience(audience).issueTime(new Date()).expirationTime(Date.from(expiry))
                        .claim("nonce", nonce).claim("email", "operator@example.com").build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
