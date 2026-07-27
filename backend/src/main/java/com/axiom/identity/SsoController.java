package com.axiom.identity;

import com.axiom.auth.AuthController;
import com.axiom.auth.CrmRole;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/** Identity-provider administration plus the public SAML/OIDC browser endpoints. */
@RestController
@RequestMapping("/api/v1")
public class SsoController {
    private final IdpConfigService configs;
    private final IdpCertificateAlertService certificateAlerts;
    private final SsoFlowService flows;
    private final StepUpService stepUp;
    private final JdbcTemplate jdbc;
    private final FederatedLoginService federatedLogin;
    private final IdentityCertificationService certifications;

    public SsoController(IdpConfigService configs, IdpCertificateAlertService certificateAlerts,
                         SsoFlowService flows, StepUpService stepUp, JdbcTemplate jdbc,
                         FederatedLoginService federatedLogin, IdentityCertificationService certifications) {
        this.configs = configs;
        this.certificateAlerts = certificateAlerts;
        this.flows = flows;
        this.stepUp = stepUp;
        this.jdbc = jdbc;
        this.federatedLogin = federatedLogin;
        this.certifications = certifications;
    }

    @GetMapping("/identity/idp") public List<IdpConfigService.IdpConfig> list() { requireViewer(); return configs.list(); }
    @GetMapping("/identity/idp/{id}") public IdpConfigService.IdpConfig get(@PathVariable UUID id) { requireViewer(); return configs.get(id); }

    @PostMapping("/identity/idp") @ResponseStatus(HttpStatus.CREATED)
    public IdpConfigService.IdpConfig create(@RequestBody @Valid IdpConfigService.IdpMutation request) {
        stepUp.requireStepUp("Configuring an identity provider"); return configs.create(request);
    }
    @PutMapping("/identity/idp/{id}")
    public IdpConfigService.IdpConfig update(@PathVariable UUID id,
                                             @RequestBody @Valid IdpConfigService.IdpMutation request) {
        stepUp.requireStepUp("Changing an identity provider configuration"); return configs.update(id, request);
    }
    @DeleteMapping("/identity/idp/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { stepUp.requireStepUp("Removing an identity provider"); configs.delete(id); }
    @PostMapping("/identity/idp/{id}/test") public IdpConfigService.TestResult test(@PathVariable UUID id) { requireAdmin(); return configs.test(id); }
    @PostMapping("/identity/idp/{id}/live-test") public SsoFlowService.LiveTestResult liveTest(@PathVariable UUID id) { requireAdmin(); return flows.liveTest(id); }
    @GetMapping("/identity/idp/certificate-alerts") public List<IdpCertificateAlertService.AlertRow> certificateAlerts() { requireViewer(); return certificateAlerts.history(); }
    @PostMapping("/identity/idp/certificate-alerts/sweep") public Map<String, Integer> sweepCertificateAlerts() { requireAdmin(); return Map.of("alertsCreated", certificateAlerts.sweepNow()); }
    @GetMapping("/identity/idp/route") public IdpConfigService.RoutingDecision route(@RequestParam String email) { requireViewer(); return configs.route(TenantContext.get().tenantId(), email); }
    @GetMapping("/identity/certifications") public List<IdentityCertificationService.Row> certifications(){return certifications.list();}
    @PostMapping("/identity/certifications") @ResponseStatus(HttpStatus.CREATED)
    public IdentityCertificationService.Row certify(@RequestBody IdentityCertificationService.Request request){stepUp.requireStepUp("Recording production identity-provider certification");return certifications.record(request);}

    /** Public pre-authentication start. Tenant identity is resolved from the named workspace and persisted state. */
    @PostMapping({"/sso/{id}/authorize", "/sso/oidc/{id}/authorize"})
    public ResponseEntity<SsoFlowService.AuthorizationRequest> begin(@PathVariable UUID id,
                                                      @RequestBody SsoFlowService.BeginRequest request,
                                                      HttpServletRequest http) {
        SsoFlowService.AuthorizationRequest authorization = flows.begin(id, request);
        return ResponseEntity.ok()
                .header("Set-Cookie", stateCookie(authorization.state(), authorization.protocol(), http, false).toString())
                .body(authorization);
    }

    @GetMapping("/sso/oidc/{id}/callback")
    public ResponseEntity<Void> oidcCallback(@PathVariable UUID id, @RequestParam String state,
                                             @RequestParam(required = false) String code,
                                             @RequestParam(required = false) String error,
                                             @CookieValue(name = "AXIOM_SSO_STATE", required = false) String browserState,
                                             HttpServletRequest http) {
        requireBrowserState(state, browserState);
        SsoFlowService.CallbackResult result = flows.completeOidc(id, state, code, error);
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", result.redirectUrl())
                .header("Set-Cookie", stateCookie("", "OIDC", http, true).toString()).build();
    }

    @GetMapping(value = "/sso/saml/{id}/metadata", produces = MediaType.APPLICATION_XML_VALUE)
    public String samlMetadata(@PathVariable UUID id, @RequestParam String tenantSlug) {
        return flows.publicSamlMetadata(id, tenantSlug);
    }

    @PostMapping(value = "/sso/saml/{id}/acs", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> samlAcs(@PathVariable UUID id,
                                        @RequestParam("RelayState") String relayState,
                                        @RequestParam("SAMLResponse") String samlResponse,
                                        @CookieValue(name = "AXIOM_SSO_STATE", required = false) String browserState,
                                        HttpServletRequest http) {
        requireBrowserState(relayState, browserState);
        SsoFlowService.CallbackResult result = flows.completeSaml(id, relayState, samlResponse);
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", result.redirectUrl())
                .header("Set-Cookie", stateCookie("", "SAML2", http, true).toString()).build();
    }

    public record TicketExchange(String ticket) {}
    @PostMapping("/sso/session/exchange")
    public AuthController.LoginResponse exchange(@RequestBody TicketExchange request, HttpServletRequest http) {
        return federatedLogin.exchange(request.ticket(), AuthController.clientIp(http), http.getHeader("User-Agent"));
    }

    private static ResponseCookie stateCookie(String state, String protocol, HttpServletRequest request, boolean clear) {
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        String sameSite = "SAML2".equals(protocol) && secure ? "None" : "Lax";
        return ResponseCookie.from("AXIOM_SSO_STATE", clear ? "" : digest(state))
                .httpOnly(true).secure(secure).sameSite(sameSite).path("/api/v1/sso")
                .maxAge(clear ? Duration.ZERO : Duration.ofMinutes(10)).build();
    }

    static void requireBrowserState(String state, String browserState) {
        if (state == null || browserState == null || !MessageDigest.isEqual(
                digest(state).getBytes(StandardCharsets.US_ASCII), browserState.getBytes(StandardCharsets.US_ASCII))) {
            throw new com.axiom.common.UnauthorizedException(
                    "The single sign-on response did not originate in this browser");
        }
    }

    private static String digest(String value) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void requireAdmin() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN) {
            throw new ForbiddenException("Identity-provider changes require Super Admin or Tenant Admin");
        }
    }

    private static void requireViewer() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN
                && role != CrmRole.SUPER_AUDIT && role != CrmRole.AUDITOR) {
            throw new ForbiddenException("Identity-provider configuration is visible to administrators and auditors");
        }
    }
}
