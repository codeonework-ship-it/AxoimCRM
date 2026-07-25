package com.axiom.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** HS256 JWT issue/parse (jjwt 0.12 API). Claims: tid, uid, role, name; sub=email. */
@Component
public class JwtService {

    /**
     * The development fallback in application.yml. It is committed to the repository and
     * therefore public: any holder can mint a token for any tenant with any role, including
     * SUPER_ADMIN. Booting a non-development environment with it is a critical defect, not a
     * warning, so {@link #assertSecretIsSafe} refuses to start rather than serve traffic
     * with a forgeable signing key (ADR-001: no client-supplied tenant identity is trusted —
     * that guarantee is void if the token signature can be forged).
     */
    static final String DEV_FALLBACK_SECRET = "dev-only-secret-key-change-me-please-32bytes-min";

    /** HS256 requires a key of at least 256 bits; shorter secrets are rejected by jjwt anyway. */
    static final int MIN_SECRET_BYTES = 32;

    /** Profiles permitted to run with a weak or default signing key. */
    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev", "test");

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${axiom.jwt.secret}") String secret,
                      @Value("${axiom.jwt.ttl-minutes}") long ttlMinutes,
                      Environment environment) {
        assertSecretIsSafe(secret, activeProfiles(environment));
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    private static List<String> activeProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return Arrays.asList(active.length > 0 ? active : environment.getDefaultProfiles());
    }

    /**
     * Fails fast when a deployment that is not explicitly dev/test would run with a signing
     * key that cannot be trusted. Visible for testing.
     */
    static void assertSecretIsSafe(String secret, List<String> activeProfiles) {
        boolean productionLike = activeProfiles.stream().noneMatch(NON_PRODUCTION_PROFILES::contains);
        if (!productionLike) {
            return;
        }
        if (secret == null || secret.isBlank() || DEV_FALLBACK_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "AXIOM_JWT_SECRET is not set for profile(s) " + activeProfiles + ". The application "
                    + "would fall back to the committed development key, which lets anyone forge a "
                    + "SUPER_ADMIN token for any tenant. Set AXIOM_JWT_SECRET to a unique secret of at "
                    + "least " + MIN_SECRET_BYTES + " bytes.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "AXIOM_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS256; it is "
                    + secret.getBytes(StandardCharsets.UTF_8).length + ".");
        }
    }

    /** Claim value marking a short-lived token that only satisfies MFA verification. */
    public static final String TYPE_MFA_CHALLENGE = "mfa_challenge";
    /** Claim value marking a normal access token backed by a server-side session. */
    public static final String TYPE_ACCESS = "access";

    /**
     * An issued access token together with the identifiers the caller needs to
     * persist a matching {@code identity.user_session} row. The {@code jti} is
     * what makes revocation immediate (FR-TEN-010): the filter refuses a token
     * whose session row is revoked, without waiting for expiry.
     */
    public record IssuedToken(String token, String jti, Instant issuedAt, Instant expiresAt) {}

    public Duration ttl() {
        return ttl;
    }

    public IssuedToken issueAccessToken(UUID tenantId, UUID userId, String role, String displayName,
                                        String email, boolean platformUser,
                                        UUID impersonatorId, String impersonatorEmail,
                                        String impersonatorName) {
        Instant now = Instant.now();
        Instant expiry = now.plus(ttl);
        String jti = UUID.randomUUID().toString();
        var builder = Jwts.builder()
                .id(jti)
                .subject(email)
                .claim("tid", tenantId.toString())
                .claim("uid", userId.toString())
                .claim("role", role)
                .claim("name", displayName)
                .claim("typ", TYPE_ACCESS)
                .claim("plat", platformUser)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry));
        if (impersonatorId != null) {
            // Both identities travel in the token so no request can act on one
            // without the other being visible to audit (FR-TEN-011).
            builder.claim("imp_uid", impersonatorId.toString())
                    .claim("imp_email", impersonatorEmail)
                    .claim("imp_name", impersonatorName);
        }
        return new IssuedToken(builder.signWith(key, Jwts.SIG.HS256).compact(), jti, now, expiry);
    }

    /**
     * A token that proves the password step succeeded and nothing else. It
     * carries {@code typ=mfa_challenge}, so {@link JwtAuthFilter} refuses it on
     * every endpoint except the MFA verification one.
     */
    public String issueMfaChallenge(UUID tenantId, UUID userId, String email, Duration challengeTtl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(email)
                .claim("tid", tenantId.toString())
                .claim("uid", userId.toString())
                .claim("typ", TYPE_MFA_CHALLENGE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(challengeTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException on invalid/expired/tampered tokens. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
