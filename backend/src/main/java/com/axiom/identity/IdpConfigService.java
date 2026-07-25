package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.common.SecretCipher;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant identity-provider configuration (FR-TEN-004, FR-TEN-005, FR-TEN-006).
 *
 * <p><b>Scope boundary, stated plainly.</b> Everything on our side of the
 * federation contract is real here: storage, validation, certificate parsing and
 * expiry reporting, attribute/claim mapping, domain-based routing, and a
 * test-connection facility that reports specific failures without activating
 * anything. Consuming a live SAML assertion or completing a live OIDC token
 * exchange is not implemented and is not claimed — that needs a purchased
 * identity provider to prove, and a "works on my mock" claim about SSO is exactly
 * the kind of thing that fails on a customer's first attempt.
 *
 * <p>Two safety properties matter more than the feature itself:
 *
 * <ol>
 *   <li><b>Secrets never come back out.</b> {@code client_secret} is encrypted at
 *       rest and every read returns {@link #SECRET_MASK} instead. There is no
 *       endpoint that returns it, so a compromised admin session cannot exfiltrate
 *       a working IdP credential.</li>
 *   <li><b>Local sign-in is never disabled.</b> Nothing in this class can turn it
 *       off. A misconfigured provider therefore cannot lock administrators out,
 *       which is FR-TEN-004's on-failure requirement; break-glass
 *       ({@link BreakGlassService}) is the second line, not the first.</li>
 * </ol>
 */
@Service
public class IdpConfigService {

    /** What a read endpoint returns in place of a stored secret. */
    public static final String SECRET_MASK = "••••••••(stored)";

    /** Administrators are warned this far ahead of certificate expiry (US-E01-03). */
    private static final Duration CERT_EXPIRY_WARNING = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final ObjectMapper json;

    public IdpConfigService(JdbcTemplate jdbc, SecretCipher cipher, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
        this.json = json;
    }

    public record IdpConfig(UUID id, String protocol, String displayName, boolean enabled,
                            String emailDomain, String entityId, String ssoUrl,
                            boolean certificatePresent, String clientId, String clientSecret,
                            String discoveryUrl, Map<String, String> attributeMap,
                            Instant certificateNotAfter, boolean certificateExpiringSoon,
                            Instant updatedAt) {}

    public record IdpMutation(String protocol, String displayName, Boolean enabled, String emailDomain,
                              String entityId, String ssoUrl, String certificate, String clientId,
                              String clientSecret, String discoveryUrl, Map<String, String> attributeMap) {}

    public record TestResult(boolean ready, List<String> problems, List<String> warnings,
                             Map<String, Object> inspected, String note) {}

    public record RoutingDecision(String method, UUID idpConfigId, String displayName,
                                  String protocol, String note) {}

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<IdpConfig> list() {
        return jdbc.query("""
                select id, protocol, display_name, enabled, email_domain, entity_id, sso_url,
                       certificate, client_id, client_secret_cipher, discovery_url,
                       attribute_map::text, updated_at
                from identity.idp_config
                where tenant_id = ?
                order by enabled desc, display_name
                """, (rs, i) -> map(rs), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public IdpConfig get(UUID id) {
        try {
            return jdbc.queryForObject("""
                    select id, protocol, display_name, enabled, email_domain, entity_id, sso_url,
                           certificate, client_id, client_secret_cipher, discovery_url,
                           attribute_map::text, updated_at
                    from identity.idp_config
                    where tenant_id = ? and id = ?
                    """, (rs, i) -> map(rs), TenantContext.get().tenantId(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("That identity provider is not configured in this workspace");
        }
    }

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    @Transactional
    public IdpConfig create(IdpMutation request) {
        requireAdmin();
        ImpersonationService.assertNotImpersonating("Configuring an identity provider");
        TenantContext.Principal principal = TenantContext.get();
        String protocol = protocol(request.protocol());
        String displayName = required(request.displayName(), "Give the provider a name your users will recognise.");
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        if (enabled) {
            // Enabling an incomplete provider is how a tenant discovers at 9am
            // that nobody can sign in. Validate first, refuse with specifics.
            assertReadyToEnable(protocol, request.entityId(), request.ssoUrl(), request.certificate(),
                    request.clientId(), request.clientSecret(), request.discoveryUrl());
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into identity.idp_config
                      (id, tenant_id, protocol, display_name, enabled, email_domain, entity_id, sso_url,
                       certificate, client_id, client_secret_cipher, discovery_url, attribute_map)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, id, principal.tenantId(), protocol, displayName, enabled,
                    lowerOrNull(request.emailDomain()), clean(request.entityId()), clean(request.ssoUrl()),
                    clean(request.certificate()), clean(request.clientId()),
                    cipher.encrypt(clean(request.clientSecret())), clean(request.discoveryUrl()),
                    writeMap(request.attributeMap()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Another provider in this workspace already uses that name or "
                    + "already routes that email domain. Domain routing must be unambiguous.");
        }
        audit.record("IDP_CONFIG_CREATE", "IDP_CONFIG", id,
                "Identity provider configured: " + displayName + " (" + protocol + ")",
                Map.of("protocol", protocol, "enabled", enabled,
                        "emailDomain", request.emailDomain() == null ? "catch-all" : request.emailDomain()));
        return get(id);
    }

    @Transactional
    public IdpConfig update(UUID id, IdpMutation request) {
        requireAdmin();
        ImpersonationService.assertNotImpersonating("Configuring an identity provider");
        TenantContext.Principal principal = TenantContext.get();
        IdpConfig current = get(id);
        String protocol = request.protocol() == null ? current.protocol() : protocol(request.protocol());
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        String certificate = clean(request.certificate());
        String secret = clean(request.clientSecret());
        if (enabled) {
            assertReadyToEnable(protocol,
                    coalesce(clean(request.entityId()), current.entityId()),
                    coalesce(clean(request.ssoUrl()), current.ssoUrl()),
                    certificate != null ? certificate : (current.certificatePresent() ? "(unchanged)" : null),
                    coalesce(clean(request.clientId()), current.clientId()),
                    secret != null ? secret : (current.clientSecret() != null ? "(unchanged)" : null),
                    coalesce(clean(request.discoveryUrl()), current.discoveryUrl()));
        }
        try {
            jdbc.update("""
                    update identity.idp_config set
                      protocol = ?,
                      display_name = coalesce(?, display_name),
                      enabled = ?,
                      email_domain = ?,
                      entity_id = coalesce(?, entity_id),
                      sso_url = coalesce(?, sso_url),
                      certificate = coalesce(?, certificate),
                      client_id = coalesce(?, client_id),
                      client_secret_cipher = coalesce(?, client_secret_cipher),
                      discovery_url = coalesce(?, discovery_url),
                      attribute_map = coalesce(?::jsonb, attribute_map),
                      updated_at = now()
                    where tenant_id = ? and id = ?
                    """, protocol, clean(request.displayName()), enabled, lowerOrNull(request.emailDomain()),
                    clean(request.entityId()), clean(request.ssoUrl()), certificate, clean(request.clientId()),
                    cipher.encrypt(secret), clean(request.discoveryUrl()),
                    request.attributeMap() == null ? null : writeMap(request.attributeMap()),
                    principal.tenantId(), id);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Another provider in this workspace already uses that name or "
                    + "already routes that email domain.");
        }
        audit.record("IDP_CONFIG_UPDATE", "IDP_CONFIG", id,
                "Identity provider configuration updated: " + current.displayName(),
                Map.of("enabled", enabled, "certificateRotated", certificate != null,
                        "secretRotated", secret != null));
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        requireAdmin();
        ImpersonationService.assertNotImpersonating("Removing an identity provider");
        TenantContext.Principal principal = TenantContext.get();
        IdpConfig existing = get(id);
        jdbc.update("delete from identity.sso_auth_request where tenant_id = ? and idp_config_id = ?",
                principal.tenantId(), id);
        jdbc.update("delete from identity.idp_config where tenant_id = ? and id = ?", principal.tenantId(), id);
        audit.record("IDP_CONFIG_DELETE", "IDP_CONFIG", id,
                "Identity provider removed: " + existing.displayName(),
                Map.of("protocol", existing.protocol()));
    }

    // ------------------------------------------------------------------
    // Test connection (no live redirect)
    // ------------------------------------------------------------------

    /**
     * Validates configuration completeness and parses the signing certificate,
     * <em>without</em> performing a redirect or contacting the provider. That is a
     * deliberate limit, not an oversight: an offline check tells the administrator
     * everything wrong on our side before they hand the URL to their users, and it
     * runs without activating the configuration (US-E01-03's first criterion).
     *
     * <p>What it cannot tell them: whether the provider will actually accept us.
     * That requires the live handshake this build does not do.
     */
    @Transactional(readOnly = true)
    public TestResult test(UUID id) {
        IdpConfig config = get(id);
        Map<String, Object> raw = jdbc.queryForMap("""
                select certificate, client_secret_cipher from identity.idp_config
                where tenant_id = ? and id = ?
                """, TenantContext.get().tenantId(), id);
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> inspected = new LinkedHashMap<>();
        inspected.put("protocol", config.protocol());
        inspected.put("displayName", config.displayName());
        inspected.put("routing", config.emailDomain() == null
                ? "catch-all for this workspace" : "email domain " + config.emailDomain());

        if ("SAML2".equals(config.protocol())) {
            if (isBlank(config.entityId())) problems.add("The IdP entity ID (issuer) is missing.");
            if (isBlank(config.ssoUrl())) problems.add("The single sign-on URL is missing.");
            else if (!config.ssoUrl().startsWith("https://")) {
                problems.add("The single sign-on URL must use https.");
            }
            String certificate = (String) raw.get("certificate");
            if (isBlank(certificate)) {
                problems.add("The IdP signing certificate is missing.");
            } else {
                inspectCertificate(certificate, problems, warnings, inspected);
            }
        } else {
            if (isBlank(config.clientId())) problems.add("The OIDC client ID is missing.");
            if (raw.get("client_secret_cipher") == null) problems.add("The OIDC client secret is missing.");
            boolean hasDiscovery = !isBlank(config.discoveryUrl());
            if (!hasDiscovery && isBlank(config.ssoUrl())) {
                problems.add("Provide either a discovery URL (.well-known/openid-configuration) "
                        + "or an explicit authorization endpoint.");
            }
            if (hasDiscovery && !config.discoveryUrl().startsWith("https://")) {
                problems.add("The discovery URL must use https.");
            }
            if (hasDiscovery && !config.discoveryUrl().contains("/.well-known/")) {
                warnings.add("The discovery URL does not look like a .well-known document; "
                        + "check it points at openid-configuration.");
            }
            inspected.put("pkce", "S256 code challenge generated per authorization request");
        }

        Map<String, String> map = config.attributeMap();
        if (map == null || !map.containsKey("email")) {
            problems.add("The attribute map has no \"email\" entry. Without it a signed-in user cannot be "
                    + "matched or provisioned, so sign-in would fail at the last step.");
        }
        inspected.put("attributeMap", map == null ? Map.of() : map);
        inspected.put("localSignInStillAvailable", true);

        return new TestResult(problems.isEmpty(), problems, warnings, inspected,
                problems.isEmpty()
                        ? "Configuration is complete and internally consistent. This check does not contact the "
                          + "provider: the live assertion or token exchange is not exercised by this build."
                        : "Configuration is not ready to enable. The provider was not contacted and nothing "
                          + "was activated.");
    }

    private void inspectCertificate(String certificate, List<String> problems, List<String> warnings,
                                    Map<String, Object> inspected) {
        try {
            String body = certificate
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            Instant notAfter = parsed.getNotAfter().toInstant();
            inspected.put("certificateSubject", parsed.getSubjectX500Principal().getName());
            inspected.put("certificateNotBefore", parsed.getNotBefore().toInstant().toString());
            inspected.put("certificateNotAfter", notAfter.toString());
            if (notAfter.isBefore(Instant.now())) {
                problems.add("The signing certificate expired on " + notAfter
                        + ". Rotate it before enabling, or authentication will fail immediately.");
            } else if (notAfter.isBefore(Instant.now().plus(CERT_EXPIRY_WARNING))) {
                warnings.add("The signing certificate expires on " + notAfter
                        + ", within the next 30 days. Rotate it before then.");
            }
        } catch (Exception e) {
            problems.add("The signing certificate could not be parsed as X.509. Paste the base64 PEM body "
                    + "exactly as the provider supplied it.");
        }
    }

    // ------------------------------------------------------------------
    // Domain routing (FR-TEN-006)
    // ------------------------------------------------------------------

    /**
     * Decides which provider, if any, handles a given address. Explicit domain
     * match wins over the catch-all, and no match at all means local sign-in —
     * which is always available.
     */
    @Transactional(readOnly = true)
    public RoutingDecision route(UUID tenantId, String email) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        String domain = email != null && email.contains("@")
                ? email.substring(email.indexOf('@') + 1).toLowerCase() : null;
        if (domain != null) {
            List<Map<String, Object>> matched = jdbc.queryForList("""
                    select id, display_name, protocol from identity.idp_config
                    where tenant_id = ? and enabled = true and email_domain = ?
                    """, tenantId, domain);
            if (!matched.isEmpty()) {
                Map<String, Object> row = matched.get(0);
                return new RoutingDecision("EMAIL_DOMAIN", (UUID) row.get("id"),
                        (String) row.get("display_name"), (String) row.get("protocol"),
                        "Routed by the email domain " + domain + ". Local sign-in remains available.");
            }
        }
        List<Map<String, Object>> catchAll = jdbc.queryForList("""
                select id, display_name, protocol from identity.idp_config
                where tenant_id = ? and enabled = true and email_domain is null
                """, tenantId);
        if (!catchAll.isEmpty()) {
            Map<String, Object> row = catchAll.get(0);
            return new RoutingDecision("WORKSPACE_DEFAULT", (UUID) row.get("id"),
                    (String) row.get("display_name"), (String) row.get("protocol"),
                    "Routed to this workspace's default provider. Local sign-in remains available.");
        }
        return new RoutingDecision("LOCAL", null, null, null,
                "No single sign-on provider is configured for that address; sign in with your password.");
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private IdpConfig map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String certificate = rs.getString("certificate");
        Instant notAfter = null;
        boolean expiringSoon = false;
        if (certificate != null && !certificate.isBlank()) {
            try {
                String body = certificate
                        .replace("-----BEGIN CERTIFICATE-----", "")
                        .replace("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s", "");
                X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(body)));
                notAfter = parsed.getNotAfter().toInstant();
                expiringSoon = notAfter.isBefore(Instant.now().plus(CERT_EXPIRY_WARNING));
            } catch (Exception ignored) {
                // A stored certificate that no longer parses is reported by test(),
                // not by every list call.
            }
        }
        Map<String, String> attributeMap;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = json.readValue(rs.getString("attribute_map"), Map.class);
            attributeMap = parsed;
        } catch (JsonProcessingException e) {
            attributeMap = Map.of();
        }
        return new IdpConfig(rs.getObject("id", UUID.class), rs.getString("protocol"),
                rs.getString("display_name"), rs.getBoolean("enabled"), rs.getString("email_domain"),
                rs.getString("entity_id"), rs.getString("sso_url"),
                certificate != null && !certificate.isBlank(), rs.getString("client_id"),
                // The stored secret is never returned; only whether one exists.
                rs.getString("client_secret_cipher") == null ? null : SECRET_MASK,
                rs.getString("discovery_url"), attributeMap, notAfter, expiringSoon,
                rs.getTimestamp("updated_at").toInstant());
    }

    private void assertReadyToEnable(String protocol, String entityId, String ssoUrl, String certificate,
                                     String clientId, String clientSecret, String discoveryUrl) {
        List<String> missing = new ArrayList<>();
        if ("SAML2".equals(protocol)) {
            if (isBlank(entityId)) missing.add("entity ID");
            if (isBlank(ssoUrl)) missing.add("single sign-on URL");
            if (isBlank(certificate)) missing.add("signing certificate");
        } else {
            if (isBlank(clientId)) missing.add("client ID");
            if (isBlank(clientSecret)) missing.add("client secret");
            if (isBlank(discoveryUrl) && isBlank(ssoUrl)) missing.add("discovery URL or authorization endpoint");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("This provider cannot be enabled yet — it is missing: "
                    + String.join(", ", missing) + ". Run the test-connection check for the full list. "
                    + "Local sign-in is unaffected either way.");
        }
    }

    private String writeMap(Map<String, String> map) {
        Map<String, String> effective = map == null || map.isEmpty()
                ? Map.of("email", "email", "displayName", "name") : map;
        try {
            return json.writeValueAsString(effective);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The attribute map could not be stored: " + e.getOriginalMessage());
        }
    }

    private static String protocol(String requested) {
        String value = requested == null ? "" : requested.trim().toUpperCase();
        if (!List.of("SAML2", "OIDC").contains(value)) {
            throw new IllegalArgumentException("Protocol must be SAML2 or OIDC");
        }
        return value;
    }

    private static String required(String value, String message) {
        String cleaned = clean(value);
        if (cleaned == null) throw new IllegalArgumentException(message);
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String lowerOrNull(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase();
    }

    private static String coalesce(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireAdmin() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN) {
            throw new ForbiddenException("Configuring single sign-on requires Super Admin or Tenant Admin");
        }
    }
}
