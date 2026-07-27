package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.AuthController;
import com.axiom.auth.JwtService;
import com.axiom.common.UnauthorizedException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Links a verified external subject, optionally JIT provisions, and issues one-time browser tickets. */
@Service
public class FederatedLoginService {
    private static final Duration TICKET_TTL = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final SessionService sessions;
    private final AuditService audit;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public FederatedLoginService(JdbcTemplate jdbc, JwtService jwt, SessionService sessions,
                                 AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.sessions = sessions;
        this.audit = audit;
        this.json = json;
    }

    public record Ticket(String value, String returnUri, Instant expiresAt) {}

    @Transactional
    public Ticket verified(IdpConfigService.IdpConfig config, Map<String, Object> claims, String returnUri) {
        UUID tenantId = TenantContext.get().tenantId();
        String subject = string(claims.get("sub"));
        if (subject == null || subject.isBlank()) throw new UnauthorizedException("The identity assertion has no subject");
        String emailClaim = config.attributeMap().getOrDefault("email", "email");
        String email = string(claims.get(emailClaim));
        if (email == null || !email.contains("@")) {
            throw new UnauthorizedException("The identity assertion did not contain the configured email claim \""
                    + emailClaim + "\"");
        }
        email = email.trim().toLowerCase();
        if (config.emailDomain() != null && !email.endsWith("@" + config.emailDomain().toLowerCase())) {
            throw new UnauthorizedException("The verified email does not belong to this provider's routing domain");
        }

        UUID userId = linkedUser(tenantId, config.id(), subject);
        boolean created = false;
        if (userId == null) {
            userId = existingUser(tenantId, email);
            if (userId == null) {
                if (!config.jitEnabled()) {
                    throw new UnauthorizedException("This verified identity is not provisioned in Axiom. "
                            + "Provision it through SCIM or enable just-in-time provisioning for this provider.");
                }
                userId = UUID.randomUUID();
                String nameClaim = config.attributeMap().getOrDefault("displayName", "name");
                String displayName = string(claims.get(nameClaim));
                if (displayName == null || displayName.isBlank()) displayName = email.substring(0, email.indexOf('@'));
                jdbc.update("""
                        insert into identity.app_user
                          (id, tenant_id, email, password_hash, display_name, role, active)
                        values (?, ?, ?, ?, ?, ?, true)
                        """, userId, tenantId, email, unusablePassword(), displayName, config.defaultRole());
                created = true;
            }
            jdbc.update("""
                    insert into identity.federated_identity
                      (tenant_id, idp_config_id, external_subject, user_id, email_at_link, last_claims)
                    values (?, ?, ?, ?, ?, ?::jsonb)
                    """, tenantId, config.id(), subject, userId, email, json(claims));
        } else {
            jdbc.update("""
                    update identity.federated_identity
                       set last_authenticated_at = now(), last_claims = ?::jsonb
                     where tenant_id = ? and idp_config_id = ? and external_subject = ?
                    """, json(claims), tenantId, config.id(), subject);
        }
        Boolean active = jdbc.queryForObject(
                "select active from identity.app_user where tenant_id = ? and id = ?", Boolean.class, tenantId, userId);
        if (!Boolean.TRUE.equals(active)) throw new UnauthorizedException("This Axiom account is inactive");

        String raw = random(32);
        String slug = jdbc.queryForObject("select slug from platform.tenant where id = ?", String.class, tenantId);
        Instant expires = Instant.now().plus(TICKET_TTL);
        jdbc.update("""
                insert into identity.sso_login_ticket
                  (tenant_id, idp_config_id, user_id, token_hash, return_uri, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, tenantId, config.id(), userId, sha256(raw), returnUri, Timestamp.from(expires));
        audit.record(created ? "FEDERATED_USER_JIT_CREATE" : "FEDERATED_LOGIN_VERIFIED", "APP_USER", userId,
                created ? "Federated identity provisioned and verified" : "Federated identity verified",
                Map.of("idpConfigId", config.id().toString(), "protocol", config.protocol(), "subject", subject));
        return new Ticket(slug + "." + raw, returnUri, expires);
    }

    @Transactional
    public AuthController.LoginResponse exchange(String ticket, String ip, String userAgent) {
        String[] parts = ticket == null ? new String[0] : ticket.split("\\.", 2);
        if (parts.length != 2) throw new UnauthorizedException("The single sign-on ticket is invalid");
        UUID tenantId;
        try {
            tenantId = jdbc.queryForObject("select id from platform.tenant where lower(slug) = lower(?)",
                    UUID.class, parts[0]);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("The single sign-on ticket is invalid");
        }
        bind(tenantId);
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    update identity.sso_login_ticket
                       set consumed_at = now()
                     where tenant_id = ? and token_hash = ? and consumed_at is null and expires_at > now()
                    returning user_id, idp_config_id
                    """, tenantId, sha256(parts[1]));
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("The single sign-on ticket is expired or was already used");
        }
        Map<String, Object> user = jdbc.queryForMap("""
                select id, email, display_name, role from identity.app_user
                where tenant_id = ? and id = ? and active
                """, tenantId, row.get("user_id"));
        Map<String, Object> tenant = jdbc.queryForMap(
                "select id, slug, name from platform.tenant where id = ? and status in ('active','suspended','terminating')",
                tenantId);
        UUID userId = (UUID) user.get("id");
        String email = (String) user.get("email");
        String displayName = (String) user.get("display_name");
        String role = (String) user.get("role");
        JwtService.IssuedToken issued = jwt.issueAccessToken(tenantId, userId, role, displayName, email,
                false, null, null, null);
        sessions.createSession(tenantId, userId, false, email, displayName, role, issued.jti(),
                "FEDERATED", issued.issuedAt(), issued.expiresAt(), ip, userAgent, null, null);
        return new AuthController.LoginResponse(issued.token(),
                new AuthController.UserDto(userId, displayName, email, role, false),
                new AuthController.TenantDto(tenantId, (String) tenant.get("slug"), (String) tenant.get("name")),
                false, null, "Signed in through your organization's identity provider.");
    }

    private UUID linkedUser(UUID tenant, UUID idp, String subject) {
        try {
            return jdbc.queryForObject("""
                    select user_id from identity.federated_identity
                    where tenant_id = ? and idp_config_id = ? and external_subject = ?
                    """, UUID.class, tenant, idp, subject);
        } catch (EmptyResultDataAccessException e) { return null; }
    }

    private UUID existingUser(UUID tenant, String email) {
        try {
            return jdbc.queryForObject("select id from identity.app_user where tenant_id = ? and lower(email)=lower(?)",
                    UUID.class, tenant, email);
        } catch (EmptyResultDataAccessException e) { return null; }
    }

    private void bind(UUID tenantId) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        TenantContext.set(new TenantContext.Principal(tenantId, tenantId, "INTEGRATION",
                "Federated sign-in", "federation@axiom.local"));
    }

    private String unusablePassword() { return "{federated-no-local-login}" + random(32); }
    private String random(int bytes) { byte[] raw = new byte[bytes]; random.nextBytes(raw); return Base64.getUrlEncoder().withoutPadding().encodeToString(raw); }
    private static String sha256(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String json(Map<String, Object> claims) {
        try { return json.writeValueAsString(claims); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Identity claims could not be recorded", e); }
    }
    private static String string(Object value) {
        if (value instanceof java.util.List<?> list) return list.isEmpty() ? null : String.valueOf(list.get(0));
        return value == null ? null : String.valueOf(value);
    }
}
