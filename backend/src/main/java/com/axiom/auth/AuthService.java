package com.axiom.auth;

import com.axiom.common.UnauthorizedException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticates tenant users and platform users. Platform identities never bypass
 * RLS: selecting another tenant issues a newly signed JWT carrying that tenant id,
 * after which the normal tenant session binding applies.
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

    public record AuthResult(String token, UUID userId, String displayName, String email,
                             String role, boolean platformUser, UUID tenantId,
                             String tenantSlug, String tenantName) {}

    public record TenantOption(UUID id, String slug, String name) {}

    @Transactional
    public AuthResult login(String tenantSlug, String email, String password) {
        Map<String, Object> tenant = activeTenant(tenantSlug);
        UUID tenantId = (UUID) tenant.get("id");

        Map<String, Object> platform = optionalPlatformUser(email);
        if (platform != null && bcrypt.matches(password, (String) platform.get("password_hash"))) {
            return result(platform, true, tenant);
        }

        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        Map<String, Object> user;
        try {
            user = jdbc.queryForMap(
                    "select id, email, password_hash, display_name, role "
                            + "from app_user where tenant_id = ? and lower(email) = lower(?) and active = true",
                    tenantId, email);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!bcrypt.matches(password, (String) user.get("password_hash"))) {
            throw new UnauthorizedException("Invalid credentials");
        }
        return result(user, false, tenant);
    }

    @Transactional(readOnly = true)
    public List<TenantOption> tenants() {
        TenantContext.Principal principal = TenantContext.get();
        if (!CrmRole.current(principal.role()).platform()) {
            return jdbc.query("select id, slug, name from tenant where id = ? and status = 'active'",
                    (rs, i) -> new TenantOption(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name")),
                    principal.tenantId());
        }
        return jdbc.query("select id, slug, name from tenant where status = 'active' order by name",
                (rs, i) -> new TenantOption(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name")));
    }

    @Transactional(readOnly = true)
    public AuthResult switchTenant(String tenantSlug) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requirePlatform(principal.role());
        Map<String, Object> tenant = activeTenant(tenantSlug);
        Map<String, Object> platform = optionalPlatformUser(principal.email());
        if (platform == null || !platform.get("id").equals(principal.userId())) {
            throw new UnauthorizedException("Platform identity is no longer active");
        }
        return result(platform, true, tenant);
    }

    private Map<String, Object> activeTenant(String tenantSlug) {
        try {
            return jdbc.queryForMap(
                    "select id, slug, name from tenant where lower(slug) = lower(?) and status = 'active'",
                    tenantSlug);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid credentials");
        }
    }

    private Map<String, Object> optionalPlatformUser(String email) {
        try {
            return jdbc.queryForMap(
                    "select id, email, password_hash, display_name, role from platform_user "
                            + "where lower(email) = lower(?) and active = true", email);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private AuthResult result(Map<String, Object> user, boolean platform, Map<String, Object> tenant) {
        UUID userId = (UUID) user.get("id");
        String email = (String) user.get("email");
        String role = (String) user.get("role");
        String displayName = (String) user.get("display_name");
        UUID tenantId = (UUID) tenant.get("id");
        String token = jwtService.issue(tenantId, userId, role, displayName, email);
        return new AuthResult(token, userId, displayName, email, role, platform,
                tenantId, (String) tenant.get("slug"), (String) tenant.get("name"));
    }
}
