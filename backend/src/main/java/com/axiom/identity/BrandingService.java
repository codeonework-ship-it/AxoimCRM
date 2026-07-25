package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant login branding (FR-TEN-015).
 *
 * <p>The read is unauthenticated by necessity — the sign-in screen needs it before
 * anybody has signed in. Two consequences were designed for rather than tripped
 * over:
 *
 * <ol>
 *   <li><b>An unknown slug returns the default branding, not a 404.</b> A
 *       distinguishable response would turn this endpoint into a workspace
 *       enumeration oracle for anyone who wanted a customer list.</li>
 *   <li><b>Only presentation fields are exposed.</b> Logo, colour, support contact
 *       and a sign-in message. Nothing about users, policy or configuration
 *       crosses the unauthenticated boundary.</li>
 * </ol>
 *
 * <p>The tenant is resolved server-side from the slug and then bound to
 * {@code app.tenant_id}, so the branding row is still fetched under RLS — the same
 * path {@code AuthService} already uses to resolve a workspace at sign-in.
 */
@Service
public class BrandingService {

    private static final String DEFAULT_COLOUR = "#0b5fbe";
    private static final String DEFAULT_MESSAGE = "Authorized use only. Sign-in activity is recorded.";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public BrandingService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Branding(String workspaceSlug, String workspaceName, String logoUrl, String primaryColour,
                           String supportContact, String signInMessage) {}

    public record BrandingMutation(String logoUrl, String primaryColour, String supportContact,
                                   String signInMessage) {}

    /** Unauthenticated read used by the sign-in screen. */
    @Transactional(readOnly = true)
    public Branding publicBranding(String slug) {
        String cleaned = slug == null ? "" : slug.trim();
        Map<String, Object> tenant;
        try {
            tenant = jdbc.queryForMap("""
                    select id, slug, name from platform.tenant
                    where lower(slug) = lower(?) and status in ('active','provisioning','suspended')
                    """, cleaned);
        } catch (EmptyResultDataAccessException e) {
            return new Branding(cleaned, null, null, DEFAULT_COLOUR, null, DEFAULT_MESSAGE);
        }
        UUID tenantId = (UUID) tenant.get("id");
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        try {
            return jdbc.queryForObject("""
                    select logo_url, primary_colour, support_contact, sign_in_message
                    from platform.tenant_branding where tenant_id = ?
                    """, (rs, i) -> new Branding(
                    (String) tenant.get("slug"), (String) tenant.get("name"),
                    rs.getString("logo_url"),
                    rs.getString("primary_colour") == null ? DEFAULT_COLOUR : rs.getString("primary_colour"),
                    rs.getString("support_contact"),
                    rs.getString("sign_in_message") == null ? DEFAULT_MESSAGE : rs.getString("sign_in_message")),
                    tenantId);
        } catch (EmptyResultDataAccessException e) {
            return new Branding((String) tenant.get("slug"), (String) tenant.get("name"), null,
                    DEFAULT_COLOUR, null, DEFAULT_MESSAGE);
        }
    }

    @Transactional(readOnly = true)
    public Branding current() {
        TenantContext.Principal principal = TenantContext.get();
        String slug = jdbc.queryForObject("select slug from platform.tenant where id = ?",
                String.class, principal.tenantId());
        return publicBranding(slug);
    }

    @Transactional
    public Branding update(BrandingMutation request) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole role = CrmRole.current(principal.role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN) {
            throw new ForbiddenException("Changing sign-in branding requires Super Admin or Tenant Admin");
        }
        String colour = clean(request.primaryColour());
        if (colour != null && !colour.matches("^#[0-9a-fA-F]{6}$")) {
            throw new IllegalArgumentException("Give the accent colour as a six-digit hex value, for example #0b5fbe");
        }
        String logo = clean(request.logoUrl());
        if (logo != null && !(logo.startsWith("https://") || logo.startsWith("/"))) {
            // An http logo would downgrade the sign-in page; a data: URI would let
            // arbitrary content render on an unauthenticated screen.
            throw new IllegalArgumentException("The logo URL must be an https address or a path within Axiom");
        }
        jdbc.update("""
                insert into platform.tenant_branding
                  (tenant_id, logo_url, primary_colour, support_contact, sign_in_message)
                values (?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set
                  logo_url = excluded.logo_url,
                  primary_colour = excluded.primary_colour,
                  support_contact = excluded.support_contact,
                  sign_in_message = excluded.sign_in_message,
                  updated_at = now()
                """, principal.tenantId(), logo, colour, clean(request.supportContact()),
                clean(request.signInMessage()));
        audit.record("BRANDING_UPDATE", "TENANT_BRANDING", principal.tenantId(),
                "Sign-in branding updated", Map.of(
                        "primaryColour", colour == null ? "default" : colour,
                        "logoSet", logo != null,
                        "supportContactSet", clean(request.supportContact()) != null));
        return current();
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
