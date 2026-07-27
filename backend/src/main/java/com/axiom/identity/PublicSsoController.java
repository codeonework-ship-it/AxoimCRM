package com.axiom.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Privacy-preserving, pre-authentication identity-provider discovery. */
@RestController
@RequestMapping("/api/v1/public/sso")
public class PublicSsoController {
    private final JdbcTemplate jdbc;

    public PublicSsoController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PublicRoute(String method, String idpConfigId, String displayName,
                              String protocol, boolean handshakeAvailable, String message) {}

    private static final PublicRoute PASSWORD = new PublicRoute(
            "PASSWORD", null, null, null, false,
            "This workspace does not use single sign-on. Sign in with your Axiom credentials.");

    @GetMapping("/route")
    @Transactional
    public PublicRoute route(@RequestParam(required = false) String tenantSlug,
                             @RequestParam(required = false) String email) {
        String slug = tenantSlug == null ? "" : tenantSlug.trim().toLowerCase(Locale.ROOT);
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (slug.isEmpty() || address.isEmpty()) return PASSWORD;

        // Unknown workspaces and workspaces without SSO deliberately look identical.
        List<UUID> tenants = jdbc.queryForList(
                "select id from platform.tenant where lower(slug) = ? and status <> 'deleted'",
                UUID.class, slug);
        if (tenants.isEmpty()) return PASSWORD;
        UUID tenantId = tenants.get(0);
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());

        int at = address.lastIndexOf('@');
        String domain = at > 0 && at < address.length() - 1 ? address.substring(at + 1) : null;
        if (domain == null) return PASSWORD;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, display_name, protocol
                from identity.idp_config
                where tenant_id = ? and enabled
                  and (lower(email_domain) = ? or email_domain is null)
                order by (email_domain is null), display_name
                limit 1
                """, tenantId, domain);
        if (rows.isEmpty()) return PASSWORD;

        Map<String, Object> idp = rows.get(0);
        String name = (String) idp.get("display_name");
        return new PublicRoute("SSO", String.valueOf(idp.get("id")), name,
                (String) idp.get("protocol"), true,
                "Continue with " + name + ". Local Axiom sign-in remains available for recovery.");
    }
}
