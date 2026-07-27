package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.NotFoundException;
import com.axiom.common.UnauthorizedException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Complete multi-tenant OIDC authorization-code/PKCE and SAML HTTP-Redirect/POST federation flow. */
@Service
public class SsoFlowService {
    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final IdpConfigService configs;
    private final OidcTokenValidator oidcValidator;
    private final SamlAssertionValidator samlValidator;
    private final FederatedLoginService login;
    private final AuditService audit;
    private final ObjectMapper json;
    private final String publicBaseUrl;
    private final String frontendLoginUrl;
    private final boolean allowInsecureLocalhost;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final SecureRandom random = new SecureRandom();

    public SsoFlowService(JdbcTemplate jdbc, IdpConfigService configs, OidcTokenValidator oidcValidator,
                          SamlAssertionValidator samlValidator, FederatedLoginService login, AuditService audit,
                          ObjectMapper json,
                          @Value("${axiom.sso.public-base-url:http://localhost:8080}") String publicBaseUrl,
                          @Value("${axiom.sso.frontend-login-url:http://localhost:4280/login}") String frontendLoginUrl,
                          @Value("${axiom.sso.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this.jdbc = jdbc;
        this.configs = configs;
        this.oidcValidator = oidcValidator;
        this.samlValidator = samlValidator;
        this.login = login;
        this.audit = audit;
        this.json = json;
        this.publicBaseUrl = strip(publicBaseUrl);
        this.frontendLoginUrl = frontendLoginUrl;
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    public record BeginRequest(String tenantSlug, String returnUri) {}
    public record AuthorizationRequest(String redirectUrl, String protocol, String state,
                                       String codeChallengeMethod, Instant expiresAt) {}
    public record CallbackResult(String redirectUrl, Instant ticketExpiresAt) {}
    public record LiveTestResult(boolean ready, String protocol, Map<String, Object> inspected,
                                 String message, Instant testedAt) {}
    private record RequestRow(UUID id, UUID tenantId, UUID idpId, String protocol, String verifier,
                              String redirectUri, String nonce, String requestId, String returnUri) {}

    @Transactional
    public AuthorizationRequest begin(UUID idpId, BeginRequest request) {
        bind(request.tenantSlug(), idpId);
        IdpConfigService.FederationConfig federation = configs.federation(idpId);
        IdpConfigService.IdpConfig config = federation.publicConfig();
        if (!config.enabled()) throw new IllegalArgumentException("That identity provider is not enabled");
        String returnUri = safeReturnUri(request.returnUri());
        return "OIDC".equals(config.protocol()) ? beginOidc(config, returnUri) : beginSaml(config, returnUri);
    }

    private AuthorizationRequest beginOidc(IdpConfigService.IdpConfig config, String returnUri) {
        Map<String, Object> discovery = discovery(config.discoveryUrl());
        String authorizationEndpoint = endpoint(config.ssoUrl(), discovery, "authorization_endpoint");
        String issuer = string(discovery.get("issuer"));
        if (config.entityId() != null && issuer != null && !config.entityId().equals(issuer)) {
            throw new IllegalArgumentException("The discovery issuer does not match the configured issuer/entity ID");
        }
        String state = tenantSlug() + "." + random(32);
        String verifier = random(48);
        String challenge = s256(verifier);
        String nonce = random(32);
        String redirect = publicBaseUrl + "/api/v1/sso/oidc/" + config.id() + "/callback";
        Instant expires = persist(config.id(), "OIDC", state, verifier, redirect, nonce, null, returnUri);
        String url = authorizationEndpoint + (authorizationEndpoint.contains("?") ? "&" : "?")
                + "response_type=code&client_id=" + enc(config.clientId()) + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc("openid email profile") + "&state=" + enc(state) + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
        audit.record("OIDC_AUTHORIZE", "IDP_CONFIG", config.id(), "OIDC authorization request created",
                Map.of("pkce", "S256", "nonce", true));
        return new AuthorizationRequest(url, "OIDC", state, "S256", expires);
    }

    private AuthorizationRequest beginSaml(IdpConfigService.IdpConfig config, String returnUri) {
        String state = tenantSlug() + "." + random(32);
        String requestId = "_" + UUID.randomUUID();
        String acs = publicBaseUrl + "/api/v1/sso/saml/" + config.id() + "/acs";
        String issue = Instant.now().toString();
        String xml = "<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
                + "xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"" + requestId
                + "\" Version=\"2.0\" IssueInstant=\"" + issue + "\" Destination=\"" + esc(config.ssoUrl())
                + "\" AssertionConsumerServiceURL=\"" + esc(acs) + "\" ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\">"
                + "<saml:Issuer>" + esc(entityId()) + "</saml:Issuer>"
                + "<samlp:NameIDPolicy Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress\" AllowCreate=\"true\"/>"
                + "</samlp:AuthnRequest>";
        Instant expires = persist(config.id(), "SAML2", state, random(24), acs, null, requestId, returnUri);
        String url = config.ssoUrl() + (config.ssoUrl().contains("?") ? "&" : "?")
                + "SAMLRequest=" + enc(deflate(xml)) + "&RelayState=" + enc(state);
        audit.record("SAML_AUTHN_REQUEST", "IDP_CONFIG", config.id(), "SAML authentication request created",
                Map.of("binding", "HTTP-Redirect", "requestId", requestId));
        return new AuthorizationRequest(url, "SAML2", state, null, expires);
    }

    @Transactional
    public CallbackResult completeOidc(UUID idpId, String state, String code, String error) {
        RequestRow request = request(idpId, state, "OIDC");
        if (error != null) throw new UnauthorizedException("The identity provider refused sign-in: " + error);
        if (code == null || code.isBlank()) throw new UnauthorizedException("The OIDC callback has no authorization code");
        IdpConfigService.FederationConfig federation = configs.federation(idpId);
        Map<String, Object> discovery = discovery(federation.publicConfig().discoveryUrl());
        String tokenEndpoint = endpoint(null, discovery, "token_endpoint");
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri="
                + enc(request.redirectUri()) + "&code_verifier=" + enc(request.verifier());
        HttpRequest.Builder tokenBuilder = HttpRequest.newBuilder(safeOutbound(tokenEndpoint)).timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded").header(HttpHeaders.ACCEPT, "application/json");
        if ("CLIENT_SECRET_POST".equals(federation.publicConfig().clientAuthMethod())) {
            form += "&client_id=" + enc(federation.publicConfig().clientId()) + "&client_secret=" + enc(federation.clientSecret());
        } else {
            String credentials = Base64.getEncoder().encodeToString((enc(federation.publicConfig().clientId()) + ":"
                    + enc(federation.clientSecret())).getBytes(StandardCharsets.UTF_8));
            tokenBuilder.header(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        }
        HttpRequest tokenRequest = tokenBuilder.POST(HttpRequest.BodyPublishers.ofString(form)).build();
        Map<String, Object> token = sendJson(tokenRequest, "OIDC token endpoint");
        String idToken = string(token.get("id_token"));
        if (idToken == null) throw new UnauthorizedException("The OIDC token response has no ID token");
        String issuer = string(discovery.get("issuer"));
        String jwksUri = string(discovery.get("jwks_uri"));
        Map<String, Object> claims = oidcValidator.validate(idToken, fetchText(jwksUri, "OIDC JWKS"), issuer,
                federation.publicConfig().clientId(), request.nonce(), CLOCK_SKEW);
        consume(request);
        FederatedLoginService.Ticket ticket = login.verified(federation.publicConfig(), claims, request.returnUri());
        return new CallbackResult(ticketRedirect(ticket), ticket.expiresAt());
    }

    @Transactional
    public CallbackResult completeSaml(UUID idpId, String relayState, String samlResponse) {
        RequestRow request = request(idpId, relayState, "SAML2");
        IdpConfigService.FederationConfig federation = configs.federation(idpId);
        Map<String, Object> claims = samlValidator.validate(samlResponse, federation.certificate(),
                federation.publicConfig().entityId(), entityId(), request.redirectUri(), request.requestId(), CLOCK_SKEW);
        consume(request);
        FederatedLoginService.Ticket ticket = login.verified(federation.publicConfig(), claims, request.returnUri());
        return new CallbackResult(ticketRedirect(ticket), ticket.expiresAt());
    }

    @Transactional
    public LiveTestResult liveTest(UUID idpId) {
        IdpConfigService.FederationConfig federation = configs.federation(idpId);
        Map<String, Object> inspected = new LinkedHashMap<>();
        try {
            if ("OIDC".equals(federation.publicConfig().protocol())) {
                Map<String, Object> discovery = discovery(federation.publicConfig().discoveryUrl());
                inspected.put("issuer", discovery.get("issuer"));
                inspected.put("authorizationEndpoint", discovery.get("authorization_endpoint"));
                inspected.put("tokenEndpoint", discovery.get("token_endpoint"));
                inspected.put("jwksUri", discovery.get("jwks_uri"));
                fetchText(string(discovery.get("jwks_uri")), "OIDC JWKS");
            } else {
                inspected.put("ssoEndpoint", safeOutbound(federation.publicConfig().ssoUrl()).toString());
                inspected.put("certificatePresent", federation.certificate() != null);
                inspected.put("metadata", samlServiceProviderMetadata(idpId, tenantSlug()));
            }
            configs.recordLiveTest(idpId, true, "Live federation prerequisites validated");
            return new LiveTestResult(true, federation.publicConfig().protocol(), inspected,
                    "Live federation prerequisites validated. Complete a real browser sign-in to certify the provider assertion.", Instant.now());
        } catch (RuntimeException e) {
            configs.recordLiveTest(idpId, false, e.getMessage());
            throw e;
        }
    }

    public String samlServiceProviderMetadata(UUID idpConfigId, String tenantSlug) {
        String entity = publicBaseUrl + "/saml/" + tenantSlug;
        String acs = publicBaseUrl + "/api/v1/sso/saml/" + idpConfigId + "/acs";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:SPSSODescriptor AuthnRequestsSigned="false" WantAssertionsSigned="true"
                    protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>
                    <md:AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                      Location="%s" index="0" isDefault="true"/>
                  </md:SPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(entity, acs);
    }

    @Transactional(readOnly = true)
    public String publicSamlMetadata(UUID idpConfigId, String tenantSlug) {
        bind(tenantSlug, idpConfigId);
        IdpConfigService.IdpConfig config = configs.get(idpConfigId);
        if (!"SAML2".equals(config.protocol())) throw new IllegalArgumentException("That provider is not SAML 2.0");
        return samlServiceProviderMetadata(idpConfigId, tenantSlug);
    }

    private Instant persist(UUID idp, String protocol, String state, String verifier, String redirect,
                            String nonce, String requestId, String returnUri) {
        Instant expires = Instant.now().plus(REQUEST_TTL);
        jdbc.update("""
                insert into identity.sso_auth_request
                  (tenant_id,idp_config_id,state,code_verifier,redirect_uri,expires_at,protocol,request_id,nonce,return_uri)
                values (?,?,?,?,?,?,?,?,?,?)
                """, TenantContext.get().tenantId(), idp, state, verifier, redirect, Timestamp.from(expires),
                protocol, requestId, nonce, returnUri);
        return expires;
    }

    private RequestRow request(UUID idpId, String state, String protocol) {
        bindFromState(state, idpId);
        try {
            return jdbc.queryForObject("""
                    select id,tenant_id,idp_config_id,protocol,code_verifier,redirect_uri,nonce,request_id,return_uri
                    from identity.sso_auth_request
                    where tenant_id=? and idp_config_id=? and state=? and protocol=? and consumed_at is null and expires_at>now()
                    """, (rs, i) -> new RequestRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getObject("idp_config_id", UUID.class), rs.getString("protocol"), rs.getString("code_verifier"),
                    rs.getString("redirect_uri"), rs.getString("nonce"), rs.getString("request_id"),
                    rs.getString("return_uri")), TenantContext.get().tenantId(), idpId, state, protocol);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("That single sign-on attempt is expired, consumed or not recognised");
        }
    }

    private void consume(RequestRow request) {
        int changed = jdbc.update("update identity.sso_auth_request set consumed_at=now() where tenant_id=? and id=? and consumed_at is null",
                request.tenantId(), request.id());
        if (changed != 1) throw new UnauthorizedException("That single sign-on response was already consumed");
    }

    private void bind(String slug, UUID idp) {
        UUID tenant;
        try { tenant = jdbc.queryForObject("select id from platform.tenant where lower(slug)=lower(?) and status<>'deleted'", UUID.class, slug); }
        catch (EmptyResultDataAccessException e) { throw new NotFoundException("That single sign-on provider is not available"); }
        bindTenant(tenant, idp);
    }

    private void bindFromState(String state, UUID idp) {
        if (state == null || !state.contains(".")) throw new UnauthorizedException("The single sign-on state is invalid");
        bind(state.substring(0, state.indexOf('.')), idp);
    }

    private void bindTenant(UUID tenant, UUID actor) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenant.toString());
        TenantContext.set(new TenantContext.Principal(tenant, actor, "INTEGRATION", "Federated sign-in", "federation@axiom.local"));
    }

    private Map<String, Object> discovery(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("OIDC discovery URL is required for live federation");
        Map<String, Object> value = sendJson(HttpRequest.newBuilder(safeOutbound(url)).timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.ACCEPT, "application/json").GET().build(), "OIDC discovery");
        for (String key : java.util.List.of("issuer", "authorization_endpoint", "token_endpoint", "jwks_uri")) {
            if (string(value.get(key)) == null) throw new IllegalArgumentException("OIDC discovery is missing " + key);
        }
        return value;
    }

    private Map<String, Object> sendJson(HttpRequest request, String label) {
        String body = send(request, label);
        try { return json.readValue(body, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalArgumentException(label + " did not return valid JSON", e); }
    }
    private String fetchText(String url, String label) {
        if (url == null) throw new IllegalArgumentException(label + " URL is missing");
        return send(HttpRequest.newBuilder(safeOutbound(url)).timeout(Duration.ofSeconds(10)).GET().build(), label);
    }
    private String send(HttpRequest request, String label) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(label + " returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException(label + " was interrupted", e); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(label + " is unreachable: " + e.getMessage(), e); }
    }

    private URI safeOutbound(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (Exception e) { throw new IllegalArgumentException("The provider URL is invalid"); }
        boolean localDev = allowInsecureLocalhost && "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !localDev) {
            throw new IllegalArgumentException("Federation endpoints must use HTTPS (localhost is allowed for certification tests)");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) throw new IllegalArgumentException("The provider URL is not safe");
        if (!allowInsecureLocalhost) {
            try {
                for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                        throw new IllegalArgumentException("Federation endpoints may not resolve to a private or local network address");
                    }
                }
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException("The federation endpoint host cannot be resolved", e);
            }
        }
        return uri;
    }

    private String endpoint(String explicit, Map<String, Object> discovery, String name) {
        String value = explicit == null || explicit.isBlank() ? string(discovery.get(name)) : explicit;
        safeOutbound(value); return value;
    }
    private String ticketRedirect(FederatedLoginService.Ticket ticket) {
        return ticket.returnUri() + (ticket.returnUri().contains("?") ? "&" : "?") + "sso_ticket=" + enc(ticket.value());
    }
    private String safeReturnUri(String requested) {
        if (requested == null || requested.isBlank()) return frontendLoginUrl;
        URI allowed = URI.create(frontendLoginUrl); URI candidate = URI.create(requested);
        if (!java.util.Objects.equals(allowed.getScheme(), candidate.getScheme())
                || !java.util.Objects.equals(allowed.getAuthority(), candidate.getAuthority())
                || !"/login".equals(candidate.getPath())) {
            throw new IllegalArgumentException("The SSO return URI is not an allowed Axiom login origin");
        }
        return candidate.toString();
    }
    private String tenantSlug() { return jdbc.queryForObject("select slug from platform.tenant where id=?", String.class, TenantContext.get().tenantId()); }
    private String entityId() { return publicBaseUrl + "/saml/" + tenantSlug(); }
    private String random(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String s256(String value) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String deflate(String xml) { try { ByteArrayOutputStream out = new ByteArrayOutputStream(); try (DeflaterOutputStream zip = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) { zip.write(xml.getBytes(StandardCharsets.UTF_8)); } return Base64.getEncoder().encodeToString(out.toByteArray()); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String enc(String v) { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8); }
    private static String esc(String v) { return v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }
    private static String string(Object v) { return v == null ? null : String.valueOf(v); }
    private static String strip(String v) { return v.endsWith("/") ? v.substring(0, v.length()-1) : v; }
}
