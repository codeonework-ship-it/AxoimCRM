package com.axiom.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant identity administration: password policy, network rules, machine
 * credentials, directory provisioning tokens, emergency access, impersonation and
 * sign-in branding.
 *
 * <p>Step-up is required on the operations that change who can get in or what a
 * credential can do (FR-TEN-009). It is applied here, at the API boundary, in
 * addition to the role checks inside each service — the service check answers "may
 * this role do it at all", the step-up answers "is the person at the keyboard
 * demonstrably still the account holder".
 */
@RestController
@RequestMapping("/api/v1/identity")
public class IdentityAdminController {

    private final PasswordPolicyService passwordPolicy;
    private final NetworkRuleService networkRules;
    private final ServiceCredentialService serviceCredentials;
    private final ScimTokenService scimTokens;
    private final BreakGlassService breakGlass;
    private final ImpersonationService impersonation;
    private final BrandingService branding;
    private final StepUpService stepUp;

    public IdentityAdminController(PasswordPolicyService passwordPolicy, NetworkRuleService networkRules,
                                   ServiceCredentialService serviceCredentials, ScimTokenService scimTokens,
                                   BreakGlassService breakGlass, ImpersonationService impersonation,
                                   BrandingService branding, StepUpService stepUp) {
        this.passwordPolicy = passwordPolicy;
        this.networkRules = networkRules;
        this.serviceCredentials = serviceCredentials;
        this.scimTokens = scimTokens;
        this.breakGlass = breakGlass;
        this.impersonation = impersonation;
        this.branding = branding;
        this.stepUp = stepUp;
    }

    public record NetworkRuleRequest(@NotBlank String cidr, String description, boolean active) {}
    public record ActiveRequest(@NotNull Boolean active) {}
    public record ReasonRequest(@NotBlank String reason) {}
    public record ConsentRequest(@NotNull Boolean consented, @NotBlank String reason) {}

    // ---------------------------------------------------------------- password policy

    @GetMapping("/password-policy")
    public PasswordPolicyService.Policy passwordPolicy() {
        return passwordPolicy.policy(com.axiom.tenancy.TenantContext.get().tenantId());
    }

    @PutMapping("/password-policy")
    public PasswordPolicyService.Policy updatePasswordPolicy(
            @RequestBody @Valid PasswordPolicyService.Policy request) {
        stepUp.requireStepUp("Changing the password policy");
        return passwordPolicy.updatePolicy(request);
    }

    // ---------------------------------------------------------------- network rules

    @GetMapping("/network-rules")
    public List<NetworkRuleService.NetworkRule> networkRules() {
        return networkRules.list();
    }

    @PostMapping("/network-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public NetworkRuleService.NetworkRule createNetworkRule(@RequestBody @Valid NetworkRuleRequest request) {
        stepUp.requireStepUp("Adding a sign-in network rule");
        return networkRules.create(request.cidr(), request.description(), request.active());
    }

    @PatchMapping("/network-rules/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setNetworkRuleActive(@PathVariable UUID id, @RequestBody @Valid ActiveRequest request) {
        stepUp.requireStepUp("Changing a sign-in network rule");
        networkRules.setActive(id, request.active(), com.axiom.tenancy.TenantContext.clientIp());
    }

    @DeleteMapping("/network-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNetworkRule(@PathVariable UUID id) {
        stepUp.requireStepUp("Removing a sign-in network rule");
        networkRules.delete(id);
    }

    // ---------------------------------------------------------------- service credentials

    @GetMapping("/service-credentials")
    public List<ServiceCredentialService.CredentialRow> serviceCredentials() {
        return serviceCredentials.list();
    }

    @PostMapping("/service-credentials")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceCredentialService.IssuedCredential issueServiceCredential(
            @RequestBody @Valid ServiceCredentialService.IssueRequest request) {
        stepUp.requireStepUp("Issuing a service credential");
        return serviceCredentials.issue(request);
    }

    @PostMapping("/service-credentials/{id}/rotate")
    public ServiceCredentialService.IssuedCredential rotateServiceCredential(@PathVariable UUID id) {
        stepUp.requireStepUp("Rotating a service credential");
        return serviceCredentials.rotate(id);
    }

    @PostMapping("/service-credentials/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeServiceCredential(@PathVariable UUID id, @RequestBody @Valid ReasonRequest request) {
        stepUp.requireStepUp("Revoking a service credential");
        serviceCredentials.revoke(id, request.reason());
    }

    // ---------------------------------------------------------------- SCIM tokens

    @GetMapping("/scim-tokens")
    public List<ScimTokenService.TokenRow> scimTokens() {
        return scimTokens.list();
    }

    @PostMapping("/scim-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScimTokenService.IssuedToken issueScimToken(
            @RequestBody @Valid ScimTokenService.IssueRequest request) {
        stepUp.requireStepUp("Issuing a directory provisioning token");
        return scimTokens.issue(request);
    }

    @PostMapping("/scim-tokens/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeScimToken(@PathVariable UUID id, @RequestBody @Valid ReasonRequest request) {
        stepUp.requireStepUp("Revoking a directory provisioning token");
        scimTokens.revoke(id, request.reason());
    }

    // ---------------------------------------------------------------- break-glass

    @GetMapping("/break-glass")
    public List<BreakGlassService.Grant> breakGlassGrants() {
        return breakGlass.list();
    }

    @PostMapping("/break-glass")
    @ResponseStatus(HttpStatus.CREATED)
    public BreakGlassService.Grant requestBreakGlass(
            @RequestBody @Valid BreakGlassService.GrantRequest request) {
        stepUp.requireStepUp("Requesting emergency administrative access");
        return breakGlass.request(request);
    }

    @PostMapping("/break-glass/{id}/use")
    public BreakGlassService.Grant useBreakGlass(@PathVariable UUID id) {
        stepUp.requireStepUp("Using emergency administrative access");
        return breakGlass.use(id);
    }

    @PostMapping("/break-glass/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeBreakGlass(@PathVariable UUID id, @RequestBody @Valid ReasonRequest request) {
        breakGlass.revoke(id, request.reason());
    }

    // ---------------------------------------------------------------- impersonation

    @GetMapping("/impersonation")
    public Map<String, Object> impersonationHistory() {
        return Map.of("consented", impersonation.consent(), "sessions", impersonation.list());
    }

    @PostMapping("/impersonation/consent")
    public Map<String, Object> setConsent(@RequestBody @Valid ConsentRequest request) {
        stepUp.requireStepUp("Changing the support impersonation consent setting");
        return Map.of("consented", impersonation.setConsent(request.consented(), request.reason()));
    }

    @PostMapping("/impersonation/start")
    public ImpersonationService.StartResult startImpersonation(
            @RequestBody @Valid ImpersonationService.StartRequest request) {
        stepUp.requireStepUp("Starting a support impersonation");
        return impersonation.start(request);
    }

    @PostMapping("/impersonation/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopImpersonation(@PathVariable UUID id, @RequestBody @Valid ReasonRequest request) {
        impersonation.stop(id, request.reason());
    }

    // ---------------------------------------------------------------- branding

    @GetMapping("/branding")
    public BrandingService.Branding branding() {
        return branding.current();
    }

    @PutMapping("/branding")
    public BrandingService.Branding updateBranding(
            @RequestBody @Valid BrandingService.BrandingMutation request) {
        return branding.update(request);
    }
}
