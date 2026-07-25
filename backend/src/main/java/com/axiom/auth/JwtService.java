package com.axiom.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** HS256 JWT issue/parse (jjwt 0.12 API). Claims: tid, uid, role, name; sub=email. */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${axiom.jwt.secret}") String secret,
                      @Value("${axiom.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(UUID tenantId, UUID userId, String role, String displayName, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("tid", tenantId.toString())
                .claim("uid", userId.toString())
                .claim("role", role)
                .claim("name", displayName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
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
