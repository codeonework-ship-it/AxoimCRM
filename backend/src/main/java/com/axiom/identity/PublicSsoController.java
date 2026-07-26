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

/**
 * Sign-in-page SSO discovery: "does this workspace sign this address in through
 * an identity provider, and which one?"
 *
 * <p>This exists because the authenticated {@code /identity/idp/route} cannot
 * serve the login page. Nobody has a token yet at that point, so the SSO button
 * called it, got a 401, and every single sign-on attempt silently fell through
 * to the password form. The button was decorative. Discovery has to run before
 * authentication or it cannot run at all — this is the same call Entra, Okta and
 * Google make from their own sign-in pages.
 *
 * <p><b>What it deliberately does not do.</b> It takes a workspace and one
 * address and answers about that pair only. It will not list a workspace's
 * configured domains, name providers for addresses it was not asked about, or
 * confirm that a user exists — an address with no provider and an address that
 * was never heard of return the identical body. The concern that stopped the
 * authenticated endpoint being opened up was domain enumeration, and asking
 * one question at a time is what prevents it.
 *
 * <p><b>What it cannot deliver.</b> Nothing here starts a handshake. Completing
 * one needs a live provider to issue an assertion or exchange a code, which this
 * build does not integrate with — {@link SsoController} returns {@code 501} at
 * both of those points and says so. So the honest answer to "is SSO ready" has
 * two parts: routing and configuration are real and testable, assertion exchange
 * is not, and {@code handshakeAvailable} carries that distinction to the sign-in
 * page instead of letting it discover the gap mid-redirect.
 */
@RestController
@RequestMapping("/api/v1/public/sso")
public class PublicSsoController {

    private final JdbcTemplate jdbc;

    public PublicSsoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param method            SSO or PASSWORD — what the sign-in page should offer
     * @param handshakeAvailable whether the redirect can actually complete today
     * @param message           user-facing wording; the page shows this verbatim
     */
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

        // An unknown workspace answers exactly as a known one with no provider.
        // Anything else turns this into a workspace-existence oracle.
        List<UUID> tenants = jdbc.queryForList(
                "select id from platform.tenant where lower(slug) = ? and status <> 'deleted'",
                UUID.class, slug);
        if (tenants.isEmpty()) return PASSWORD;
        UUID tenantId = tenants.get(0);

        // RLS on identity.idp_config is keyed to app.tenant_id, so bind it to the
        // workspace that was named rather than reading across tenants. The row is
        // still filtered on tenant_id in the predicate — belt and braces, per ADR-001.
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());

        int at = address.lastIndexOf('@');
        String domain = at > 0 && at < address.length() - 1 ? address.substring(at + 1) : null;
        if (domain == null) return PASSWORD;

        // Domain-specific configuration wins; a provider with no domain is the
        // workspace-wide fallback. Ordering does that in one query.
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
        return new PublicRoute(
                "SSO",
                String.valueOf(idp.get("id")),
                name,
                (String) idp.get("protocol"),
                false,
                name + " is configured for this workspace, but completing the sign-in handshake needs a "
                        + "live connection to it that this build does not make. Sign in with your Axiom "
                        + "credentials — local sign-in is never disabled.");
    }
}
