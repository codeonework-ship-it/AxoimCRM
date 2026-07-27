package com.axiom.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict OIDC ID-token verification. No unsigned, symmetric or provider-supplied inline key is trusted. */
@Component
public class OidcTokenValidator {

    public Map<String, Object> validate(String compact, String jwksJson, String expectedIssuer,
                                        String clientId, String expectedNonce, Duration skew) {
        try {
            SignedJWT jwt = SignedJWT.parse(compact);
            JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
            if (!JWSAlgorithm.Family.RSA.contains(alg)) {
                throw new IllegalArgumentException("The OIDC provider used unsupported signing algorithm " + alg
                        + "; Axiom accepts the RSA algorithms advertised through the provider JWKS.");
            }
            String kid = jwt.getHeader().getKeyID();
            if (kid == null || kid.isBlank()) throw new IllegalArgumentException("The ID token has no key id (kid)");
            JWK key = JWKSet.parse(jwksJson).getKeyByKeyId(kid);
            if (!(key instanceof RSAKey rsa) || !jwt.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))) {
                throw new IllegalArgumentException("The ID token signature does not match the provider JWKS");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            Duration allowance = skew == null ? Duration.ofMinutes(2) : skew;
            if (!expectedIssuer.equals(claims.getIssuer())) throw new IllegalArgumentException("ID token issuer mismatch");
            if (!claims.getAudience().contains(clientId)) throw new IllegalArgumentException("ID token audience mismatch");
            if (claims.getAudience().size() > 1 && !clientId.equals(claims.getStringClaim("azp"))) {
                throw new IllegalArgumentException("ID token authorized-party (azp) mismatch");
            }
            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().plus(allowance).isBefore(now)) {
                throw new IllegalArgumentException("The ID token is expired");
            }
            if (claims.getIssueTime() == null
                    || claims.getIssueTime().toInstant().minus(allowance).isAfter(now)) {
                throw new IllegalArgumentException("The ID token issue time is invalid");
            }
            if (claims.getNotBeforeTime() != null
                    && claims.getNotBeforeTime().toInstant().minus(allowance).isAfter(now)) {
                throw new IllegalArgumentException("The ID token is not active yet");
            }
            if (!expectedNonce.equals(claims.getStringClaim("nonce"))) {
                throw new IllegalArgumentException("ID token nonce mismatch; the callback may be replayed");
            }
            if (claims.getSubject() == null || claims.getSubject().isBlank()) {
                throw new IllegalArgumentException("The ID token has no subject claim");
            }
            return new LinkedHashMap<>(claims.getClaims());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("The OIDC ID token could not be validated: " + e.getMessage(), e);
        }
    }
}
