package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * The parts of the SSO handshake that are ours to build (FR-TEN-004, FR-TEN-005).
 *
 * <p>Concretely: the OIDC authorization request including a real S256 PKCE
 * challenge and single-use state, persisted so the callback can be validated; and
 * the SAML service-provider metadata a tenant administrator uploads to their IdP.
 * Both are fully functional and testable without a vendor.
 *
 * <p>What is <b>not</b> here, and is not claimed anywhere: consuming a signed SAML
 * assertion, and exchanging an authorization code for tokens at a live provider.
 * Those endpoints exist and refuse with {@code 501 Not Implemented} and an
 * explanation, rather than pretending or silently doing nothing. A stub that
 * returns 200 is worse than one that refuses — it looks like a working feature
 * right up to the point a customer relies on it.
 */
@Service
public class SsoFlowService {

    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final IdpConfigService idpConfigs;
    private final AuditService audit;
    private final String publicBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public SsoFlowService(JdbcTemplate jdbc, IdpConfigService idpConfigs, AuditService audit,
                          @Value("${axiom.sso.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.idpConfigs = idpConfigs;
        this.audit = audit;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    public record AuthorizationRequest(String authorizationUrl, String state, String codeChallengeMethod,
                                       String redirectUri, Instant expiresAt, String note) {}

    @Transactional
    public AuthorizationRequest beginOidc(UUID idpConfigId) {
        IdpConfigService.IdpConfig config = idpConfigs.get(idpConfigId);
        if (!"OIDC".equals(config.protocol())) {
            throw new IllegalArgumentException("That provider is configured for SAML 2.0, not OIDC");
        }
        if (!config.enabled()) {
            throw new IllegalArgumentException("That provider is not enabled. Run the test-connection check, "
                    + "fix what it reports, then enable it.");
        }
        String authorizationEndpoint = config.ssoUrl() != null ? config.ssoUrl()
                : derivedAuthorizationEndpoint(config.discoveryUrl());
        if (authorizationEndpoint == null) {
            throw new IllegalArgumentException("No authorization endpoint is configured and none could be "
                    + "derived from the discovery URL. Set the authorization endpoint explicitly.");
        }
        String state = randomUrlSafe(32);
        String verifier = randomUrlSafe(48);
        String challenge = s256(verifier);
        String redirectUri = publicBaseUrl + "/api/v1/sso/oidc/" + idpConfigId + "/callback";
        Instant expiresAt = Instant.now().plus(REQUEST_TTL);
        jdbc.update("""
                insert into identity.sso_auth_request
                  (tenant_id, idp_config_id, state, code_verifier, redirect_uri, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, TenantContext.get().tenantId(), idpConfigId, state, verifier, redirectUri,
                Timestamp.from(expiresAt));
        String url = authorizationEndpoint
                + (authorizationEndpoint.contains("?") ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + enc(config.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256";
        audit.record("SSO_AUTHORIZE_BUILT", "IDP_CONFIG", idpConfigId,
                "OIDC authorization request prepared for " + config.displayName(),
                Map.of("protocol", "OIDC", "pkce", "S256"));
        return new AuthorizationRequest(url, state, "S256", redirectUri, expiresAt,
                "This URL, the state and the PKCE challenge are generated and stored by Axiom. Completing the "
                        + "exchange needs a live provider and is not implemented in this build.");
    }

    /**
     * Validates and consumes a callback state. Real work, and the reason it stops
     * short: the code-for-token exchange is an outbound call to a provider we do
     * not have.
     */
    @Transactional
    public void assertCallbackStateValid(UUID idpConfigId, String state) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    select id, expires_at, consumed_at from identity.sso_auth_request
                    where tenant_id = ? and idp_config_id = ? and state = ?
                    """, TenantContext.get().tenantId(), idpConfigId, state);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("That sign-in attempt is not recognised. Start again from the sign-in page.");
        }
        if (row.get("consumed_at") != null) {
            throw new IllegalArgumentException("That sign-in attempt was already completed. Start again.");
        }
        if (((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new IllegalArgumentException("That sign-in attempt expired. Start again from the sign-in page.");
        }
        jdbc.update("update identity.sso_auth_request set consumed_at = now() where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), row.get("id"));
    }

    /** Service-provider metadata for a tenant administrator to upload to their IdP. */
    public String samlServiceProviderMetadata(UUID idpConfigId, String tenantSlug) {
        String entityId = publicBaseUrl + "/saml/" + tenantSlug;
        String acs = publicBaseUrl + "/api/v1/sso/saml/" + idpConfigId + "/acs";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:SPSSODescriptor AuthnRequestsSigned="false" WantAssertionsSigned="true"
                                      protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>
                    <md:AssertionConsumerService
                        Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                        Location="%s" index="0" isDefault="true"/>
                  </md:SPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(entityId, acs);
    }

    private static String derivedAuthorizationEndpoint(String discoveryUrl) {
        if (discoveryUrl == null) return null;
        // Deliberately does NOT fetch the document: no outbound call is made from
        // this build. A conventional issuer layout is assumed, and the
        // test-connection check tells the administrator to set the endpoint
        // explicitly if their provider differs.
        int wellKnown = discoveryUrl.indexOf("/.well-known/");
        if (wellKnown < 0) return null;
        return discoveryUrl.substring(0, wellKnown) + "/protocol/openid-connect/auth";
    }

    private String randomUrlSafe(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
