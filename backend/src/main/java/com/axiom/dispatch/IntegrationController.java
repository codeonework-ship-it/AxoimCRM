package com.axiom.dispatch;

import com.axiom.auth.CrmRole;
import com.axiom.integration.AdapterRegistry;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Integration dispatch API.
 *
 * <p>Nothing here can return a secret: {@link NamedCredentialService.CredentialRow}
 * has no field for one, connector configuration passes through
 * {@link ConfigSanitiser}, and credential resolution is package-visible so no
 * controller can call it.
 */
@RestController
@RequestMapping("/api/v1/integration")
@Validated
public class IntegrationController {

    private final ConnectorService connectors;
    private final NamedCredentialService credentials;
    private final DispatchService dispatch;
    private final IntegrationHealthService health;
    private final DispatchWorker worker;

    public IntegrationController(ConnectorService connectors, NamedCredentialService credentials,
                                 DispatchService dispatch, IntegrationHealthService health,
                                 DispatchWorker worker) {
        this.connectors = connectors;
        this.credentials = credentials;
        this.dispatch = dispatch;
        this.health = health;
        this.worker = worker;
    }

    /* ---------------- connectors ---------------- */

    @GetMapping("/connectors")
    public List<ConnectorService.ConnectorRow> connectors() {
        return connectors.list();
    }

    @GetMapping("/connectors/{connectorId}")
    public ConnectorService.ConnectorRow connector(@PathVariable UUID connectorId) {
        return connectors.get(connectorId);
    }

    @PostMapping("/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectorService.ConnectorRow createConnector(@RequestBody @Valid ConnectorRequest request) {
        return connectors.create(request);
    }

    @PutMapping("/connectors/{connectorId}")
    public ConnectorService.ConnectorRow updateConnector(@PathVariable UUID connectorId,
                                                        @RequestBody @Valid ConnectorRequest request) {
        return connectors.update(connectorId, request);
    }

    @PatchMapping("/connectors/{connectorId}/enabled")
    public ConnectorService.ConnectorRow setEnabled(@PathVariable UUID connectorId,
                                                    @RequestParam boolean enabled) {
        return connectors.setEnabled(connectorId, enabled);
    }

    @DeleteMapping("/connectors/{connectorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConnector(@PathVariable UUID connectorId) {
        connectors.delete(connectorId);
    }

    @GetMapping("/adapters")
    public List<AdapterRegistry.AdapterDescriptor> adapters() {
        return connectors.adapterCatalogue();
    }

    /* ---------------- subscriptions ---------------- */

    @GetMapping("/connectors/{connectorId}/subscriptions")
    public List<ConnectorService.SubscriptionRow> subscriptions(@PathVariable UUID connectorId) {
        return connectors.subscriptions(connectorId);
    }

    @PostMapping("/connectors/{connectorId}/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectorService.SubscriptionRow addSubscription(@PathVariable UUID connectorId,
                                                            @RequestBody @Valid SubscriptionRequest request) {
        return connectors.addSubscription(connectorId, request);
    }

    @PutMapping("/connectors/{connectorId}/subscriptions/{subscriptionId}")
    public ConnectorService.SubscriptionRow updateSubscription(@PathVariable UUID connectorId,
                                                               @PathVariable UUID subscriptionId,
                                                               @RequestBody @Valid SubscriptionRequest request) {
        return connectors.updateSubscription(connectorId, subscriptionId, request);
    }

    @DeleteMapping("/connectors/{connectorId}/subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubscription(@PathVariable UUID connectorId, @PathVariable UUID subscriptionId) {
        connectors.deleteSubscription(connectorId, subscriptionId);
    }

    /* ---------------- named credentials (FR-INT-007) ---------------- */

    @GetMapping("/credentials")
    public List<NamedCredentialService.CredentialRow> credentials() {
        return credentials.list();
    }

    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    public NamedCredentialService.CredentialRow createCredential(@RequestBody @Valid CredentialRequest request) {
        return credentials.create(request);
    }

    @PostMapping("/credentials/{name}/rotate")
    public NamedCredentialService.CredentialRow rotateCredential(@PathVariable String name,
                                                                 @RequestBody @Valid SecretRotation rotation) {
        return credentials.rotate(name, rotation);
    }

    @DeleteMapping("/credentials/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCredential(@PathVariable String name) {
        credentials.delete(name);
    }

    /* ---------------- dispatch log ---------------- */

    @GetMapping("/deliveries")
    public List<DispatchService.DeliveryRow> deliveries(@RequestParam(required = false) UUID connectorId,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "100") int limit) {
        return dispatch.deliveries(connectorId, status, limit);
    }

    @GetMapping("/deliveries/{deliveryId}/attempts")
    public List<DispatchService.AttemptRow> attempts(@PathVariable UUID deliveryId) {
        return dispatch.attempts(deliveryId);
    }

    /* ---------------- dead letters and replay ---------------- */

    @GetMapping("/dead-letters")
    public List<DispatchService.DeadLetterRow> deadLetters(@RequestParam(required = false) UUID connectorId,
                                                           @RequestParam(defaultValue = "false") boolean includeReplayed,
                                                           @RequestParam(defaultValue = "100") int limit) {
        return dispatch.deadLetters(connectorId, includeReplayed, limit);
    }

    @PostMapping("/dead-letters/{deadLetterId}/replay")
    public DispatchService.ReplayOutcome replayOne(@PathVariable UUID deadLetterId) {
        return dispatch.replay(List.of(deadLetterId)).get(0);
    }

    @PostMapping("/dead-letters/replay")
    public List<DispatchService.ReplayOutcome> replayMany(@RequestBody @Valid ReplayRequest request) {
        return dispatch.replay(request.deadLetterIds());
    }

    @PostMapping("/dead-letters/replay-all")
    public List<DispatchService.ReplayOutcome> replayAll(@RequestParam(required = false) UUID connectorId,
                                                         @RequestParam(defaultValue = "100") int limit) {
        return dispatch.replayAll(connectorId, limit);
    }

    /* ---------------- health (FR-INT-009) ---------------- */

    @GetMapping("/health")
    public List<IntegrationHealthService.HealthRow> health() {
        return health.connectors();
    }

    @GetMapping("/health/summary")
    public IntegrationHealthService.HealthSummary healthSummary() {
        return health.summary();
    }

    /**
     * Force a dispatch tick for this tenant.
     *
     * <p>The worker binds its own system principal and clears the thread's
     * tenant context when it finishes, so the request's principal is captured
     * and restored around the call — otherwise everything after this line in the
     * request would run unbound.
     */
    @PostMapping("/worker/run")
    public DispatchWorker.TickResult runWorker() {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireMasterAdmin(principal.role());
        try {
            return worker.runForTenant(principal.tenantId());
        } finally {
            TenantContext.set(principal);
        }
    }
}
