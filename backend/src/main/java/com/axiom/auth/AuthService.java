package com.axiom.auth;

import com.axiom.common.UnauthorizedException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Login flow. This is the ONE place that must read tenant-scoped data
 * (app_user) before any tenant context exists — a chicken-and-egg with the
 * RLS policies of ADR-001, resolved as follows:
 *
 * <ol>
 *   <li>Resolve the tenant by slug from the {@code tenant} table, which is a
 *       platform table deliberately left WITHOUT row-level security (V1) —
 *       a slug is not tenant data, it is the address of a tenant.</li>
 *   <li>Inside the same transaction, bind the freshly resolved tenant id to
 *       the Postgres session with {@code set_config('app.tenant_id', ?, true)}
 *       (SET LOCAL semantics — dies with the transaction, so the pooled
 *       connection carries no residual identity). This is safe because the
 *       tenant id came from OUR database via the trusted slug lookup, never
 *       from a client-supplied identifier.</li>
 *   <li>Now the {@code app_user} query passes the RLS policy and can only
 *       ever see users of that single tenant — even a bug in the email
 *       predicate could not authenticate a user of another tenant.</li>
 * </ol>
 *
 * TenantContext is NOT bound during login (the aspect is skipped), which is
 * why the set_config call is made explicitly here. Password verification is
 * bcrypt via spring-security-crypto against the pgcrypto-generated hash.
 */
@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AuthService(JdbcTemplate jdbc, JwtService jwtService) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
    }

    public record AuthResult(String token,
                             UUID userId, String displayName, String email, String role,
                             UUID tenantId, String tenantSlug, String tenantName) {}

    @Transactional
    public AuthResult login(String tenantSlug, String email, String password) {
        // 1. Tenant by slug — tenant table has no RLS; only active tenants may log in.
        Map<String, Object> tenant;
        try {
            tenant = jdbc.queryForMap(
                    "select id, slug, name from tenant where slug = ? and status = 'active'",
                    tenantSlug);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid credentials");
        }
        UUID tenantId = (UUID) tenant.get("id");

        // 2. Bind the resolved (trusted) tenant id for this transaction so the
        //    app_user RLS policy opens for exactly this tenant.
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());

        // 3. Tenant-scoped user lookup — RLS is now the second wall around this query.
        Map<String, Object> user;
        try {
            user = jdbc.queryForMap(
                    "select id, email, password_hash, display_name, role "
                            + "from app_user where tenant_id = ? and email = ?",
                    tenantId, email);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid credentials");
        }

        if (!bcrypt.matches(password, (String) user.get("password_hash"))) {
            throw new UnauthorizedException("Invalid credentials");
        }

        UUID userId = (UUID) user.get("id");
        String role = (String) user.get("role");
        String displayName = (String) user.get("display_name");
        String token = jwtService.issue(tenantId, userId, role, displayName, email);

        return new AuthResult(token, userId, displayName, email, role,
                tenantId, (String) tenant.get("slug"), (String) tenant.get("name"));
    }
}
