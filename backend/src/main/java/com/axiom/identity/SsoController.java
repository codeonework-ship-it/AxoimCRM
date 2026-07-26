package com.axiom.identity;

import com.axiom.tenancy.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single sign-on configuration and the parts of the handshake Axiom owns
 * (FR-TEN-004, FR-TEN-005, FR-TEN-006).
 *
 * <p>The two endpoints that would need a live identity provider —
 * {@code POST /sso/saml/{id}/acs} and {@code POST /sso/oidc/{id}/callback} —
 * validate everything they can on our side and then return
 * {@code 501 Not Implemented} with an explanation. That is a deliberate choice
 * over a stub that returns success: a customer discovering the difference during
 * their own SSO cutover is a far worse outcome than reading it here.
 */
@RestController
@RequestMapping("/api/v1")
public class SsoController {

    private final IdpConfigService configs;
    private final IdpCertificateAlertService certificateAlerts;
    private final SsoFlowService flows;
    private final StepUpService stepUp;
    private final JdbcTemplate jdbc;

    public SsoController(IdpConfigService configs, IdpCertificateAlertService certificateAlerts,
                         SsoFlowService flows, StepUpService stepUp, JdbcTemplate jdbc) {
        this.configs = configs;
        this.certificateAlerts = certificateAlerts;
        this.flows = flows;
        this.stepUp = stepUp;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- configuration

    @GetMapping("/identity/idp")
    public List<IdpConfigService.IdpConfig> list() {
        return configs.list();
    }

    @GetMapping("/identity/idp/{id}")
    public IdpConfigService.IdpConfig get(@PathVariable UUID id) {
        return configs.get(id);
    }

    @PostMapping("/identity/idp")
    @ResponseStatus(HttpStatus.CREATED)
    public IdpConfigService.IdpConfig create(@RequestBody @Valid IdpConfigService.IdpMutation request) {
        stepUp.requireStepUp("Configuring an identity provider");
        return configs.create(request);
    }

    @PutMapping("/identity/idp/{id}")
    public IdpConfigService.IdpConfig update(@PathVariable UUID id,
                                             @RequestBody @Valid IdpConfigService.IdpMutation request) {
        stepUp.requireStepUp("Changing an identity provider configuration");
        return configs.update(id, request);
    }

    @DeleteMapping("/identity/idp/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        stepUp.requireStepUp("Removing an identity provider");
        configs.delete(id);
    }

    /** Validates configuration without activating anything or contacting the provider. */
    @PostMapping("/identity/idp/{id}/test")
    public IdpConfigService.TestResult test(@PathVariable UUID id) {
        return configs.test(id);
    }

    @GetMapping("/identity/idp/certificate-alerts")
    public List<IdpCertificateAlertService.AlertRow> certificateAlerts() {
        return certificateAlerts.history();
    }

    @PostMapping("/identity/idp/certificate-alerts/sweep")
    public Map<String, Integer> sweepCertificateAlerts() {
        return Map.of("alertsCreated", certificateAlerts.sweepNow());
    }

    // ---------------------------------------------------------------- routing

    /**
     * Which provider would handle an address (FR-TEN-006). Authenticated, because an
     * unauthenticated version would let anyone map a workspace's email domains.
     */
    @GetMapping("/identity/idp/route")
    public IdpConfigService.RoutingDecision route(@RequestParam String email) {
        return configs.route(TenantContext.get().tenantId(), email);
    }

    // ---------------------------------------------------------------- flows

    @PostMapping("/sso/oidc/{id}/authorize")
    public SsoFlowService.AuthorizationRequest beginOidc(@PathVariable UUID id) {
        return flows.beginOidc(id);
    }

    @PostMapping("/sso/oidc/{id}/callback")
    public ResponseEntity<Map<String, Object>> oidcCallback(@PathVariable UUID id,
                                                            @RequestParam String state,
                                                            @RequestParam(required = false) String code) {
        // Real work: the state is validated and consumed, so a replayed callback is
        // rejected regardless of what comes next.
        flows.assertCallbackStateValid(id, state);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SSO_EXCHANGE_NOT_IMPLEMENTED");
        body.put("message", "The authorization state was validated and consumed. Exchanging the code for "
                + "tokens requires a live identity provider, which this build does not integrate with. "
                + "Sign in with a password in the meantime — local sign-in is never disabled.");
        body.put("stateValidated", true);
        body.put("codeReceived", code != null);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
    }

    /** Service-provider metadata for the tenant administrator to upload to their IdP. */
    @GetMapping(value = "/sso/saml/{id}/metadata", produces = MediaType.APPLICATION_XML_VALUE)
    public String samlMetadata(@PathVariable UUID id) {
        configs.get(id); // 404s for another tenant's id before anything is emitted
        String slug = jdbc.queryForObject("select slug from platform.tenant where id = ?",
                String.class, TenantContext.get().tenantId());
        return flows.samlServiceProviderMetadata(id, slug);
    }

    @PostMapping("/sso/saml/{id}/acs")
    public ResponseEntity<Map<String, Object>> samlAcs(@PathVariable UUID id) {
        configs.get(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SAML_ASSERTION_NOT_IMPLEMENTED");
        body.put("message", "This is the assertion consumer endpoint the metadata advertises. Verifying a "
                + "signed SAML assertion requires a live identity provider to issue one, which this build "
                + "does not integrate with. Configuration, certificate validation, attribute mapping and "
                + "domain routing are implemented and testable; assertion consumption is not.");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
    }
}
