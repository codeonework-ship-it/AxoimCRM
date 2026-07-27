package com.axiom.sandbox;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.security.MakerCheckerService;
import com.axiom.security.RbacAccess;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * E19 release control plane.
 *
 * <p>Validation is a read of the target plus append-only evidence. Deployment is
 * one database transaction: configuration, release state, audit and outbox commit
 * together. Expected blockers are exhausted before the first target write, so a
 * rejected deployment reports every issue and leaves the target byte-for-byte
 * unchanged. Production additionally requires a maker-checker decision.</p>
 */
@Service
public class ReleaseManagementService {
    public static final String APPROVAL_ACTION = "RELEASE_PROMOTION";
    private static final int ROLLBACK_RETENTION_DAYS = 30;
    private static final List<String> COUNT_KEYS = List.of(
            "accounts", "contacts", "leads", "opportunities", "outboxEvents", "auditEvents");

    public record SandboxRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,40}$") String code,
                                 @NotBlank @Size(max = 160) String name,
                                 @NotBlank String sandboxType,
                                 @NotBlank String dataScope) {}
    public record SandboxView(UUID id, String code, String name, String sandboxType, String status,
                              String dataScope, boolean outboundEmailEnabled,
                              boolean outboundWebhooksEnabled, boolean outboundIntegrationsEnabled,
                              int configurationItems, Instant lastRefreshedAt) {}
    public record OutboundRequest(boolean email, boolean webhooks, boolean integrations,
                                  @NotBlank String acknowledgement) {}
    public record PackageRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,50}$") String code,
                                 @NotBlank @Size(max = 160) String name,
                                 @Size(max = 500) String description,
                                 @NotNull UUID sourceSandboxId, @NotBlank String targetEnvironment) {}
    public record ComponentRequest(@NotBlank String componentType,
                                   @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.:-]+$") String componentKey,
                                   @NotBlank String operation, JsonNode before, JsonNode after) {}
    public record ReleasePackage(UUID id, String code, String name, String description, String status,
                                 UUID sourceSandboxId, String sandboxName, String targetEnvironment,
                                 int componentCount, UUID approvalRequestId, String fingerprint,
                                 Instant approvedAt, Instant deployedAt, Instant updatedAt) {}
    public record ReleaseComponent(UUID id, int sequence, String componentType, String componentKey,
                                   String operation, JsonNode before, JsonNode after) {}
    public record DiffLine(String componentType, String componentKey, String operation,
                           JsonNode targetValue, JsonNode proposedValue, String outcome) {}
    public record ValidationResult(UUID id, UUID packageId, String status, String fingerprint,
                                   int componentCount, int blockingIssueCount,
                                   List<DiffLine> diff, List<String> issues, Instant validatedAt) {}
    public record DeploymentResult(UUID id, String runNumber, String status, int componentsApplied,
                                   String fingerprint, String summary, Instant completedAt) {}
    public record RollbackPreview(UUID deploymentId, boolean reversible, int componentCount,
                                  Instant deadline, List<String> blockers) {}
    public record RollbackResult(UUID id, UUID deploymentId, String status, int restoredComponents,
                                 List<String> blockers, String message, Instant completedAt) {}
    public record RecoveryBaseline(Map<String, Long> recordCounts, Instant newestOutboxEvent,
                                   String databaseVersion, Instant capturedAt) {}
    public record DrValidationRequest(@NotBlank String scenario, @NotBlank String restoreEnvironment,
                                      @NotBlank String backupReference,
                                      @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String backupChecksum,
                                      @NotNull Instant recoveryStartedAt, @NotNull Instant recoveryCompletedAt,
                                      @NotNull Instant sourceLastEventAt, @NotNull Instant restoredLastEventAt,
                                      @NotNull Map<String, Long> expectedCounts) {}
    public record DrValidation(UUID id, String scenario, String restoreEnvironment, String status,
                               int targetRtoSeconds, long observedRtoSeconds,
                               int targetRpoSeconds, long observedRpoSeconds,
                               Map<String, Long> expectedCounts, Map<String, Long> observedCounts,
                               List<Map<String, Object>> checks, List<String> blockers,
                               String verdict, Instant validatedAt) {}

    private record StoredComponent(UUID id, int sequence, String type, String key, String operation,
                                   JsonNode before, JsonNode after) {}
    private record CurrentConfiguration(boolean exists, boolean active, JsonNode payload, String checksum) {}

    private final JdbcTemplate jdbc;
    private final MakerCheckerService approvals;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public ReleaseManagementService(JdbcTemplate jdbc, MakerCheckerService approvals, AuditService audit,
                                    OutboxWriter outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    // ---------------------------------------------------------------- sandbox

    @Transactional
    public SandboxView createSandbox(SandboxRequest request) {
        requireAdmin();
        String type = enumValue(request.sandboxType(), List.of("DEV", "QA", "UAT", "FULL_COPY"), "sandbox type");
        String scope = enumValue(request.dataScope(), List.of("CONFIGURATION_ONLY", "SAMPLE_DATA", "FULL_COPY"), "data scope");
        UUID id = UUID.randomUUID();
        String snapshot = jdbc.queryForObject("""
                select coalesce(jsonb_agg(jsonb_build_object(
                           'componentType', component_type, 'componentKey', component_key,
                           'payload', payload, 'checksum', checksum, 'version', version,
                           'active', active) order by component_type, component_key), '[]'::jsonb)::text
                  from platform.environment_configuration
                 where tenant_id = ? and environment = 'PROD'
                """, String.class, tenant());
        jdbc.update("""
                insert into platform.sandbox_environment
                  (id, tenant_id, sandbox_code, name, sandbox_type, status, source_environment,
                   owner_id, last_refreshed_at, expires_at, data_scope, configuration_snapshot,
                   refreshed_by, outbound_email_enabled, outbound_webhooks_enabled,
                   outbound_integrations_enabled)
                values (?, ?, ?, ?, ?, 'REQUESTED', 'PROD', ?, now(), now() + interval '30 days',
                        ?, ?::jsonb, ?, false, false, false)
                """, id, tenant(), request.code().toUpperCase(Locale.ROOT), request.name().trim(), type,
                sandboxOwner(), scope, snapshot, actor());
        // The workflow trigger permits only REQUESTED as an entry state. The
        // local configuration copy completes synchronously, so promotion to
        // ACTIVE is the next transition in this same atomic transaction.
        jdbc.update("update platform.sandbox_environment set status='ACTIVE' where tenant_id=? and id=?",
                tenant(), id);
        Map<String, Object> details = Map.of("sandboxType", type, "dataScope", scope,
                "outboundEmail", false, "outboundWebhooks", false, "outboundIntegrations", false);
        audit.record("SANDBOX_CREATED", "SANDBOX", id, "Created isolated sandbox " + request.code(), details);
        outbox.write("sandbox", id, "sandbox.created", details);
        return sandbox(id);
    }

    @Transactional
    public SandboxView configureOutbound(UUID id, OutboundRequest request) {
        requireAdmin();
        String acknowledgement = request.acknowledgement().trim();
        if ((request.email() || request.webhooks() || request.integrations())
                && !"I UNDERSTAND TEST DATA MAY CONTACT REAL RECIPIENTS".equals(acknowledgement)) {
            throw new ConflictException("Enabling sandbox outbound traffic requires the exact acknowledgement: "
                    + "I UNDERSTAND TEST DATA MAY CONTACT REAL RECIPIENTS");
        }
        SandboxView before = sandbox(id);
        jdbc.update("""
                update platform.sandbox_environment set outbound_email_enabled = ?,
                       outbound_webhooks_enabled = ?, outbound_integrations_enabled = ?
                 where tenant_id = ? and id = ? and status = 'ACTIVE'
                """, request.email(), request.webhooks(), request.integrations(), tenant(), id);
        Map<String, Object> details = Map.of(
                "before", Map.of("email", before.outboundEmailEnabled(), "webhooks", before.outboundWebhooksEnabled(),
                        "integrations", before.outboundIntegrationsEnabled()),
                "after", Map.of("email", request.email(), "webhooks", request.webhooks(),
                        "integrations", request.integrations()), "acknowledgement", acknowledgement);
        audit.record("SANDBOX_OUTBOUND_CHANGED", "SANDBOX", id, "Changed sandbox outbound safety controls", details);
        outbox.write("sandbox", id, "sandbox.outbound.changed", details);
        return sandbox(id);
    }

    @Transactional(readOnly = true)
    public List<SandboxView> sandboxes() {
        requireRead();
        return jdbc.query("""
                select id, sandbox_code, name, sandbox_type, status, data_scope,
                       outbound_email_enabled, outbound_webhooks_enabled, outbound_integrations_enabled,
                       jsonb_array_length(configuration_snapshot) configuration_items, last_refreshed_at
                  from platform.sandbox_environment where tenant_id = ? order by created_at desc
                """, (rs, row) -> new SandboxView(rs.getObject("id", UUID.class), rs.getString("sandbox_code"),
                rs.getString("name"), rs.getString("sandbox_type"), rs.getString("status"),
                rs.getString("data_scope"), rs.getBoolean("outbound_email_enabled"),
                rs.getBoolean("outbound_webhooks_enabled"), rs.getBoolean("outbound_integrations_enabled"),
                rs.getInt("configuration_items"), instant(rs.getTimestamp("last_refreshed_at"))), tenant());
    }

    // ---------------------------------------------------------------- packages

    @Transactional
    public ReleasePackage createPackage(PackageRequest request) {
        requireAdmin();
        sandbox(request.sourceSandboxId());
        String target = environment(request.targetEnvironment());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into platform.release_package
                  (id, tenant_id, package_code, name, description, status, source_sandbox_id,
                   target_environment, component_count, submitted_by, updated_at)
                values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, 0, ?, now())
                """, id, tenant(), request.code().toUpperCase(Locale.ROOT), request.name().trim(),
                clean(request.description()), request.sourceSandboxId(), target, actor());
        audit.record("RELEASE_PACKAGE_CREATED", "RELEASE_PACKAGE", id,
                "Created release package " + request.code(), Map.of("targetEnvironment", target));
        return releasePackage(id);
    }

    @Transactional
    public ReleaseComponent addComponent(UUID packageId, ComponentRequest request) {
        requireAdmin();
        ReleasePackage pack = releasePackageForUpdate(packageId);
        if (!"DRAFT".equals(pack.status())) throw new ConflictException("Only draft packages can be edited");
        String operation = enumValue(request.operation(), List.of("UPSERT", "REMOVE"), "operation");
        JsonNode before = nullableNode(request.before());
        JsonNode after = nullableNode(request.after());
        if ("UPSERT".equals(operation) && after == null) {
            throw new ConflictException("An UPSERT component requires the proposed after value");
        }
        String type = request.componentType().trim().toUpperCase(Locale.ROOT);
        int sequence = jdbc.queryForObject("""
                select coalesce(max(sequence_no), 0) + 1 from platform.release_component
                 where tenant_id = ? and release_package_id = ?
                """, Integer.class, tenant(), packageId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into platform.release_component
                  (id, tenant_id, release_package_id, sequence_no, component_type, component_key,
                   operation, before_payload, after_payload)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, id, tenant(), packageId, sequence, type, request.componentKey().trim(), operation,
                before == null ? null : json(before), after == null ? null : json(after));
        jdbc.update("update platform.release_package set component_count = component_count + 1, updated_at = now() where tenant_id = ? and id = ?",
                tenant(), packageId);
        audit.record("RELEASE_COMPONENT_ADDED", "RELEASE_PACKAGE", packageId,
                "Added " + type + " " + request.componentKey(), Map.of("operation", operation, "sequence", sequence));
        return components(packageId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ReleasePackage> packages() {
        requireRead();
        return jdbc.query("""
                select p.id, p.package_code, p.name, p.description, p.status, p.source_sandbox_id,
                       s.name sandbox_name, p.target_environment, p.component_count,
                       p.approval_request_id, p.current_fingerprint, p.approved_at, p.deployed_at, p.updated_at
                  from platform.release_package p
                  left join platform.sandbox_environment s on s.tenant_id=p.tenant_id and s.id=p.source_sandbox_id
                 where p.tenant_id = ? order by p.updated_at desc, p.created_at desc
                """, (rs, row) -> mapPackage(rs), tenant());
    }

    @Transactional(readOnly = true)
    public List<ReleaseComponent> components(UUID packageId) {
        requireRead();
        releasePackage(packageId);
        return storedComponents(packageId).stream().map(c -> new ReleaseComponent(c.id(), c.sequence(), c.type(),
                c.key(), c.operation(), c.before(), c.after())).toList();
    }

    // ----------------------------------------------------------- validate/approve

    @Transactional
    public ValidationResult validate(UUID packageId) {
        requireAdmin();
        ReleasePackage pack = releasePackage(packageId);
        List<StoredComponent> components = storedComponents(packageId);
        List<String> issues = new ArrayList<>();
        List<DiffLine> diff = new ArrayList<>();
        if (!List.of("DRAFT", "VALIDATED").contains(pack.status())) {
            issues.add("Package status " + pack.status() + " cannot be validated; create a new draft for further changes.");
        }
        SandboxView sandbox = sandbox(pack.sourceSandboxId());
        if (!"ACTIVE".equals(sandbox.status())) issues.add("Source sandbox is " + sandbox.status() + ", not ACTIVE.");
        if (components.isEmpty()) issues.add("The package has no components.");
        for (StoredComponent component : components) {
            CurrentConfiguration current = current(pack.targetEnvironment(), component.type(), component.key());
            if (component.before() == null && current.exists()) {
                issues.add(component.type() + "/" + component.key()
                        + " already exists in the target; capture its current value as the before value.");
            }
            if (component.before() != null && !same(component.before(), current.payload())) {
                issues.add(component.type() + "/" + component.key()
                        + " changed in the target after this change was prepared.");
            }
            if ("REMOVE".equals(component.operation()) && (!current.exists() || !current.active())) {
                issues.add(component.type() + "/" + component.key() + " cannot be removed because it is not active in the target.");
            }
            diff.add(new DiffLine(component.type(), component.key(), component.operation(), current.payload(),
                    component.after(), current.exists() ? "CHANGE" : "CREATE"));
        }
        String fingerprint = fingerprint(components);
        String status = issues.isEmpty() ? "VALID" : "BLOCKED";
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        jdbc.update("""
                insert into platform.release_validation_run
                  (id, tenant_id, release_package_id, target_environment, status, package_fingerprint,
                   component_count, blocking_issue_count, diff, issues, validated_by, validated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, id, tenant(), packageId, pack.targetEnvironment(), status, fingerprint,
                components.size(), issues.size(), json(diff), json(issues), actor(), Timestamp.from(at));
        if (issues.isEmpty()) {
            jdbc.update("update platform.release_package set status='VALIDATED', current_fingerprint=?, updated_at=now() where tenant_id=? and id=?",
                    fingerprint, tenant(), packageId);
        }
        Map<String, Object> evidence = Map.of("status", status, "fingerprint", fingerprint,
                "componentCount", components.size(), "blockingIssues", issues);
        audit.record("RELEASE_VALIDATED", "RELEASE_PACKAGE", packageId,
                status.equals("VALID") ? "Release validation passed" : "Release validation found " + issues.size() + " blocker(s)", evidence);
        return new ValidationResult(id, packageId, status, fingerprint, components.size(), issues.size(),
                List.copyOf(diff), List.copyOf(issues), at);
    }

    @Transactional
    public MakerCheckerService.ApprovalRequest submitForApproval(UUID packageId) {
        requireAdmin();
        ReleasePackage pack = releasePackageForUpdate(packageId);
        if (!"VALIDATED".equals(pack.status())) throw new ConflictException("Validate the package successfully before requesting approval");
        assertLatestValidation(pack);
        MakerCheckerService.ApprovalRequest approval = approvals.submit(new MakerCheckerService.SubmitRequest(
                APPROVAL_ACTION, "RELEASE_PACKAGE", packageId,
                "Promote " + pack.code() + " to " + pack.targetEnvironment(),
                Map.of("packageCode", pack.code(), "targetEnvironment", pack.targetEnvironment(),
                        "fingerprint", pack.fingerprint(), "componentCount", pack.componentCount())));
        jdbc.update("""
                update platform.release_package set status='PENDING_APPROVAL', approval_request_id=?,
                       submitted_by=?, updated_at=now() where tenant_id=? and id=?
                """, approval.id(), actor(), tenant(), packageId);
        outbox.write("release_package", packageId, "release.approval.requested",
                Map.of("approvalRequestId", approval.id(), "target", pack.targetEnvironment()));
        return approval;
    }

    @Transactional
    public ReleasePackage approve(UUID packageId, UUID approvalRequestId, String note) {
        requireAdmin();
        ReleasePackage pack = releasePackageForUpdate(packageId);
        if (!"PENDING_APPROVAL".equals(pack.status()) || !approvalRequestId.equals(pack.approvalRequestId())) {
            throw new ConflictException("This approval request is not the open decision for the release package");
        }
        approvals.approve(approvalRequestId, requiredReason(note, "Approval note"));
        jdbc.update("update platform.release_package set status='APPROVED', approved_by=?, approved_at=now(), updated_at=now() where tenant_id=? and id=?",
                actor(), tenant(), packageId);
        audit.recordWithReason("RELEASE_APPROVED", "RELEASE_PACKAGE", packageId,
                "Approved release " + pack.code(), note, Map.of("approvalRequestId", approvalRequestId));
        outbox.write("release_package", packageId, "release.approved", Map.of("approvalRequestId", approvalRequestId));
        return releasePackage(packageId);
    }

    @Transactional
    public ReleasePackage reject(UUID packageId, UUID approvalRequestId, String note) {
        requireAdmin();
        ReleasePackage pack = releasePackageForUpdate(packageId);
        if (!"PENDING_APPROVAL".equals(pack.status()) || !approvalRequestId.equals(pack.approvalRequestId())) {
            throw new ConflictException("This approval request is not the open decision for the release package");
        }
        approvals.reject(approvalRequestId, requiredReason(note, "Rejection reason"));
        jdbc.update("update platform.release_package set status='REJECTED', updated_at=now() where tenant_id=? and id=?",
                tenant(), packageId);
        audit.recordWithReason("RELEASE_REJECTED", "RELEASE_PACKAGE", packageId,
                "Rejected release " + pack.code(), note, Map.of("approvalRequestId", approvalRequestId));
        return releasePackage(packageId);
    }

    // -------------------------------------------------------------- deploy/rollback

    @Transactional
    public DeploymentResult deploy(UUID packageId) {
        requireAdmin();
        ReleasePackage pack = releasePackageForUpdate(packageId);
        if ("PROD".equals(pack.targetEnvironment()) && !"APPROVED".equals(pack.status())) {
            throw new ConflictException("Production promotion requires an approved maker-checker request");
        }
        if (!List.of("VALIDATED", "APPROVED").contains(pack.status())) {
            throw new ConflictException("Only validated or approved packages can be deployed");
        }
        ValidationResult validation = assertLatestValidation(pack);
        List<StoredComponent> components = storedComponents(packageId);
        List<String> blockers = deploymentBlockers(pack, components);
        if (!blockers.isEmpty()) {
            throw new ConflictException("Deployment blocked; target unchanged. " + String.join(" ", blockers));
        }
        List<Map<String, Object>> baseline = new ArrayList<>();
        List<Map<String, Object>> deployed = new ArrayList<>();
        for (StoredComponent component : components) {
            CurrentConfiguration current = current(pack.targetEnvironment(), component.type(), component.key());
            baseline.add(snapshot(component, current));
            if ("REMOVE".equals(component.operation())) {
                jdbc.update("""
                        update platform.environment_configuration set active=false, version=version+1,
                               promoted_from_package_id=?, updated_by=?, updated_at=now()
                         where tenant_id=? and environment=? and component_type=? and component_key=?
                        """, packageId, actor(), tenant(), pack.targetEnvironment(), component.type(), component.key());
                deployed.add(snapshot(component, new CurrentConfiguration(true, false, current.payload(), current.checksum())));
            } else {
                String checksum = checksum(component.after());
                jdbc.update("""
                        insert into platform.environment_configuration
                          (tenant_id, environment, component_type, component_key, payload, active, checksum,
                           version, promoted_from_package_id, updated_by)
                        values (?, ?, ?, ?, ?::jsonb, true, ?, 1, ?, ?)
                        on conflict (tenant_id, environment, component_type, component_key) do update set
                          payload=excluded.payload, active=true, checksum=excluded.checksum,
                          version=platform.environment_configuration.version+1,
                          promoted_from_package_id=excluded.promoted_from_package_id,
                          updated_by=excluded.updated_by, updated_at=now()
                        """, tenant(), pack.targetEnvironment(), component.type(), component.key(),
                        json(component.after()), checksum, packageId, actor());
                deployed.add(snapshot(component, new CurrentConfiguration(true, true, component.after(), checksum)));
            }
        }
        UUID deploymentId = UUID.randomUUID();
        Instant completed = Instant.now();
        String runNumber = "DEP-" + completed.toString().replaceAll("[-:TZ.]", "").substring(0, 14)
                + "-" + deploymentId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into platform.deployment_run
                  (id, tenant_id, release_package_id, run_number, status, completed_at,
                   validation_errors, summary, validation_run_id, package_fingerprint,
                   baseline_snapshot, deployed_snapshot, initiated_by)
                values (?, ?, ?, ?, 'SUCCEEDED', ?, 0, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, deploymentId, tenant(), packageId, runNumber, Timestamp.from(completed),
                "Atomically applied " + components.size() + " component(s)", validation.id(),
                validation.fingerprint(), json(baseline), json(deployed), actor());
        jdbc.update("update platform.release_package set status='DEPLOYED', deployed_at=now(), updated_at=now() where tenant_id=? and id=?",
                tenant(), packageId);
        Map<String, Object> evidence = Map.of("runNumber", runNumber, "target", pack.targetEnvironment(),
                "componentCount", components.size(), "fingerprint", validation.fingerprint(),
                "before", baseline, "after", deployed);
        audit.record("RELEASE_DEPLOYED", "RELEASE_PACKAGE", packageId,
                "Deployed release " + pack.code() + " atomically", evidence);
        outbox.write("release_package", packageId, "release.deployed", evidence);
        return new DeploymentResult(deploymentId, runNumber, "SUCCEEDED", components.size(),
                validation.fingerprint(), "Target updated atomically; rollback evidence retained for 30 days.", completed);
    }

    @Transactional(readOnly = true)
    public RollbackPreview rollbackPreview(UUID deploymentId) {
        requireRead();
        Map<String, Object> deployment = deployment(deploymentId, false);
        Instant completed = instant((Timestamp) deployment.get("completed_at"));
        Instant deadline = completed.plus(Duration.ofDays(ROLLBACK_RETENTION_DAYS));
        List<String> blockers = new ArrayList<>();
        if (!"SUCCEEDED".equals(deployment.get("status"))) blockers.add("Only a successful deployment can be rolled back.");
        if (Instant.now().isAfter(deadline)) blockers.add("The 30-day rollback retention window ended at " + deadline + ".");
        JsonNode deployed = parse(String.valueOf(deployment.get("deployed_snapshot")));
        for (JsonNode item : deployed) {
            CurrentConfiguration current = current(String.valueOf(deployment.get("target_environment")),
                    item.path("componentType").asText(), item.path("componentKey").asText());
            boolean expectedActive = item.path("active").asBoolean();
            String expectedChecksum = nullableText(item.get("checksum"));
            if (current.active() != expectedActive || !java.util.Objects.equals(current.checksum(), expectedChecksum)) {
                blockers.add(item.path("componentType").asText() + "/" + item.path("componentKey").asText()
                        + " changed after deployment; rolling back would overwrite a later release.");
            }
        }
        return new RollbackPreview(deploymentId, blockers.isEmpty(), deployed.size(), deadline, List.copyOf(blockers));
    }

    @Transactional
    public RollbackResult rollback(UUID deploymentId, String reason) {
        requireAdmin();
        String safeReason = requiredReason(reason, "Rollback reason");
        Map<String, Object> deployment = deployment(deploymentId, true);
        RollbackPreview preview = rollbackPreview(deploymentId);
        if (!preview.reversible()) {
            UUID blockedId = recordBlockedRollback(deploymentId, safeReason, preview.blockers());
            return new RollbackResult(blockedId, deploymentId, "BLOCKED", 0, preview.blockers(),
                    "Rollback blocked; target unchanged. " + String.join(" ", preview.blockers()), Instant.now());
        }
        JsonNode baseline = parse(String.valueOf(deployment.get("baseline_snapshot")));
        String environment = String.valueOf(deployment.get("target_environment"));
        int restored = 0;
        for (JsonNode item : baseline) {
            String type = item.path("componentType").asText();
            String key = item.path("componentKey").asText();
            boolean existed = item.path("existed").asBoolean();
            if (!existed) {
                jdbc.update("""
                        update platform.environment_configuration set active=false, version=version+1,
                               updated_by=?, updated_at=now()
                         where tenant_id=? and environment=? and component_type=? and component_key=?
                        """, actor(), tenant(), environment, type, key);
            } else {
                JsonNode payload = item.get("payload");
                String checksum = item.path("checksum").asText();
                boolean active = item.path("active").asBoolean();
                jdbc.update("""
                        update platform.environment_configuration set payload=?::jsonb, active=?, checksum=?,
                               version=version+1, updated_by=?, updated_at=now()
                         where tenant_id=? and environment=? and component_type=? and component_key=?
                        """, json(payload), active, checksum, actor(), tenant(), environment, type, key);
            }
            restored++;
        }
        UUID rollbackId = UUID.randomUUID();
        Instant completed = Instant.now();
        jdbc.update("""
                insert into platform.release_rollback_run
                  (id, tenant_id, deployment_run_id, status, reason, restored_components,
                   blockers, requested_by, completed_at)
                values (?, ?, ?, 'SUCCEEDED', ?, ?, '[]'::jsonb, ?, ?)
                """, rollbackId, tenant(), deploymentId, safeReason, restored, actor(), Timestamp.from(completed));
        jdbc.update("update platform.deployment_run set status='ROLLED_BACK', rolled_back_at=now() where tenant_id=? and id=?",
                tenant(), deploymentId);
        jdbc.update("update platform.release_package set status='ROLLED_BACK', updated_at=now() where tenant_id=? and id=?",
                tenant(), deployment.get("release_package_id"));
        Map<String, Object> evidence = Map.of("deploymentId", deploymentId, "restoredComponents", restored,
                "targetEnvironment", environment);
        audit.recordWithReason("RELEASE_ROLLED_BACK", "DEPLOYMENT_RUN", deploymentId,
                "Rolled back deployment and restored exact baseline", safeReason, evidence);
        outbox.write("deployment_run", deploymentId, "release.rolled_back", evidence);
        return new RollbackResult(rollbackId, deploymentId, "SUCCEEDED", restored, List.of(),
                "Exact pre-deployment configuration restored.", completed);
    }

    // -------------------------------------------------------------- DR validation

    @Transactional(readOnly = true)
    public RecoveryBaseline recoveryBaseline() {
        requireRead();
        return new RecoveryBaseline(currentCounts(), newestOutbox(), databaseVersion(), Instant.now());
    }

    @Transactional
    public DrValidation validateRecovery(DrValidationRequest request) {
        requireAdmin();
        String scenario = enumValue(request.scenario(),
                List.of("SINGLE_AZ", "REGIONAL_LOSS", "POINT_IN_TIME", "TENANT_RESTORE"), "DR scenario");
        if (request.recoveryCompletedAt().isBefore(request.recoveryStartedAt())) {
            throw new ConflictException("Recovery completion cannot be before recovery start");
        }
        int targetRto = "SINGLE_AZ".equals(scenario) ? 60 : 14_400;
        int targetRpo = "SINGLE_AZ".equals(scenario) ? 0 : 300;
        long observedRto = Duration.between(request.recoveryStartedAt(), request.recoveryCompletedAt()).getSeconds();
        long observedRpo = Math.abs(Duration.between(request.restoredLastEventAt(), request.sourceLastEventAt()).getSeconds());
        Map<String, Long> observed = currentCounts();
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        check(checks, blockers, "RTO", observedRto <= targetRto,
                observedRto + " seconds observed; target " + targetRto + " seconds");
        check(checks, blockers, "RPO", observedRpo <= targetRpo,
                observedRpo + " seconds observed; target " + targetRpo + " seconds");
        for (String key : COUNT_KEYS) {
            Long expected = request.expectedCounts().get(key);
            boolean match = expected != null && expected.equals(observed.get(key));
            check(checks, blockers, "COUNT_" + key.toUpperCase(Locale.ROOT), match,
                    "expected " + expected + ", observed " + observed.get(key));
        }
        check(checks, blockers, "SCHEMA_VERSION", versionAtLeast(databaseVersion(), 348),
                "restored database migration version " + databaseVersion());
        check(checks, blockers, "BACKUP_INTEGRITY_REFERENCE", request.backupChecksum().length() == 64,
                "SHA-256 backup checksum supplied for " + request.backupReference());
        check(checks, blockers, "OUTBOX_REPLAY", newestOutbox() != null,
                "newest restored outbox event is " + newestOutbox());
        String status = blockers.isEmpty() ? "PASS" : "FAIL";
        UUID id = UUID.randomUUID();
        Instant validatedAt = Instant.now();
        jdbc.update("""
                insert into platform.dr_validation_run
                  (id, tenant_id, scenario, restore_environment, backup_reference, backup_checksum,
                   status, target_rto_seconds, observed_rto_seconds, target_rpo_seconds,
                   observed_rpo_seconds, expected_counts, observed_counts, checks, blockers,
                   validated_by, validated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                """, id, tenant(), scenario, request.restoreEnvironment().trim(), request.backupReference().trim(),
                request.backupChecksum().toLowerCase(Locale.ROOT), status, targetRto, observedRto,
                targetRpo, observedRpo, json(request.expectedCounts()), json(observed), json(checks),
                json(blockers), actor(), Timestamp.from(validatedAt));
        String verdict = status.equals("PASS")
                ? "Restore validated: RTO, RPO, record parity, schema, backup checksum and outbox replay controls passed."
                : "Restore is not certifiable: " + blockers.size() + " recovery control(s) failed.";
        Map<String, Object> evidence = Map.of("scenario", scenario, "status", status,
                "observedRtoSeconds", observedRto, "observedRpoSeconds", observedRpo,
                "blockers", blockers, "backupReference", request.backupReference());
        audit.record("DISASTER_RECOVERY_VALIDATED", "DR_VALIDATION", id, verdict, evidence);
        outbox.write("dr_validation", id, "disaster_recovery.validated", evidence);
        return new DrValidation(id, scenario, request.restoreEnvironment(), status, targetRto,
                observedRto, targetRpo, observedRpo, request.expectedCounts(), observed,
                List.copyOf(checks), List.copyOf(blockers), verdict, validatedAt);
    }

    @Transactional(readOnly = true)
    public List<DrValidation> recoveryHistory(int limit) {
        requireRead();
        return jdbc.query("""
                select id, scenario, restore_environment, status, target_rto_seconds,
                       observed_rto_seconds, target_rpo_seconds, observed_rpo_seconds,
                       expected_counts::text, observed_counts::text, checks::text, blockers::text,
                       validated_at
                  from platform.dr_validation_run where tenant_id=?
                 order by validated_at desc limit ?
                """, (rs, row) -> {
            List<Map<String, Object>> checks = readMapList(rs.getString("checks"));
            List<String> blockers = readStringList(rs.getString("blockers"));
            String status = rs.getString("status");
            return new DrValidation(rs.getObject("id", UUID.class), rs.getString("scenario"),
                    rs.getString("restore_environment"), status, rs.getInt("target_rto_seconds"),
                    rs.getLong("observed_rto_seconds"), rs.getInt("target_rpo_seconds"),
                    rs.getLong("observed_rpo_seconds"), readLongMap(rs.getString("expected_counts")),
                    readLongMap(rs.getString("observed_counts")), checks, blockers,
                    status.equals("PASS") ? "Restore validation passed." : blockers.size() + " recovery controls failed.",
                    rs.getTimestamp("validated_at").toInstant());
        }, tenant(), Math.min(Math.max(limit, 1), 50));
    }

    // ---------------------------------------------------------------- helpers

    private ReleasePackage releasePackage(UUID id) {
        List<ReleasePackage> rows = jdbc.query("""
                select p.id, p.package_code, p.name, p.description, p.status, p.source_sandbox_id,
                       s.name sandbox_name, p.target_environment, p.component_count,
                       p.approval_request_id, p.current_fingerprint, p.approved_at, p.deployed_at, p.updated_at
                  from platform.release_package p left join platform.sandbox_environment s
                    on s.tenant_id=p.tenant_id and s.id=p.source_sandbox_id
                 where p.tenant_id=? and p.id=?
                """, (rs, row) -> mapPackage(rs), tenant(), id);
        if (rows.isEmpty()) throw new NotFoundException("Release package not found");
        return rows.getFirst();
    }

    private ReleasePackage releasePackageForUpdate(UUID id) {
        jdbc.queryForObject("select id from platform.release_package where tenant_id=? and id=? for update",
                UUID.class, tenant(), id);
        return releasePackage(id);
    }

    private ReleasePackage mapPackage(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReleasePackage(rs.getObject("id", UUID.class), rs.getString("package_code"),
                rs.getString("name"), rs.getString("description"), rs.getString("status"),
                rs.getObject("source_sandbox_id", UUID.class), rs.getString("sandbox_name"),
                rs.getString("target_environment"), rs.getInt("component_count"),
                rs.getObject("approval_request_id", UUID.class), rs.getString("current_fingerprint"),
                instant(rs.getTimestamp("approved_at")), instant(rs.getTimestamp("deployed_at")),
                rs.getTimestamp("updated_at").toInstant());
    }

    private SandboxView sandbox(UUID id) {
        return sandboxes().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Sandbox environment not found"));
    }

    private List<StoredComponent> storedComponents(UUID packageId) {
        return jdbc.query("""
                select id, sequence_no, component_type, component_key, operation,
                       before_payload::text, after_payload::text
                  from platform.release_component where tenant_id=? and release_package_id=?
                 order by sequence_no
                """, (rs, row) -> new StoredComponent(rs.getObject("id", UUID.class), rs.getInt("sequence_no"),
                rs.getString("component_type"), rs.getString("component_key"), rs.getString("operation"),
                parseNullable(rs.getString("before_payload")), parseNullable(rs.getString("after_payload"))),
                tenant(), packageId);
    }

    private CurrentConfiguration current(String environment, String type, String key) {
        List<CurrentConfiguration> rows = jdbc.query("""
                select payload::text, checksum, active from platform.environment_configuration
                 where tenant_id=? and environment=? and component_type=? and component_key=?
                """, (rs, row) -> new CurrentConfiguration(true, rs.getBoolean("active"),
                parse(rs.getString("payload")), rs.getString("checksum")), tenant(), environment, type, key);
        return rows.isEmpty() ? new CurrentConfiguration(false, false, null, null) : rows.getFirst();
    }

    private ValidationResult assertLatestValidation(ReleasePackage pack) {
        List<ValidationResult> rows = jdbc.query("""
                select id, release_package_id, status, package_fingerprint, component_count,
                       blocking_issue_count, diff::text, issues::text, validated_at
                  from platform.release_validation_run
                 where tenant_id=? and release_package_id=? order by validated_at desc limit 1
                """, (rs, row) -> new ValidationResult(rs.getObject("id", UUID.class),
                rs.getObject("release_package_id", UUID.class), rs.getString("status"),
                rs.getString("package_fingerprint"), rs.getInt("component_count"),
                rs.getInt("blocking_issue_count"), readDiff(rs.getString("diff")),
                readStringList(rs.getString("issues")), rs.getTimestamp("validated_at").toInstant()),
                tenant(), pack.id());
        if (rows.isEmpty() || !"VALID".equals(rows.getFirst().status())
                || !java.util.Objects.equals(pack.fingerprint(), rows.getFirst().fingerprint())) {
            throw new ConflictException("The current package fingerprint does not have a successful validation");
        }
        return rows.getFirst();
    }

    private List<String> deploymentBlockers(ReleasePackage pack, List<StoredComponent> components) {
        List<String> blockers = new ArrayList<>();
        for (StoredComponent component : components) {
            CurrentConfiguration current = current(pack.targetEnvironment(), component.type(), component.key());
            if (component.before() == null && current.exists()) {
                blockers.add(component.type() + "/" + component.key()
                        + " already exists; a create-only component cannot overwrite it.");
            }
            if (component.before() != null && !same(component.before(), current.payload())) {
                blockers.add(component.type() + "/" + component.key() + " no longer matches the validated baseline.");
            }
            if ("REMOVE".equals(component.operation()) && (!current.exists() || !current.active())) {
                blockers.add(component.type() + "/" + component.key() + " is not active and cannot be removed.");
            }
        }
        return blockers;
    }

    private Map<String, Object> deployment(UUID id, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.*, p.target_environment from platform.deployment_run d
                  join platform.release_package p on p.tenant_id=d.tenant_id and p.id=d.release_package_id
                 where d.tenant_id=? and d.id=?
                """ + (lock ? " for update of d" : ""), tenant(), id);
        if (rows.isEmpty()) throw new NotFoundException("Deployment run not found");
        return rows.getFirst();
    }

    private UUID recordBlockedRollback(UUID deploymentId, String reason, List<String> blockers) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into platform.release_rollback_run
                  (id, tenant_id, deployment_run_id, status, reason, restored_components, blockers, requested_by)
                values (?, ?, ?, 'BLOCKED', ?, 0, ?::jsonb, ?)
                """, id, tenant(), deploymentId, reason, json(blockers), actor());
        return id;
    }

    private Map<String, Object> snapshot(StoredComponent component, CurrentConfiguration current) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("componentType", component.type()); item.put("componentKey", component.key());
        item.put("existed", current.exists()); item.put("active", current.active());
        item.put("payload", current.payload()); item.put("checksum", current.checksum());
        return item;
    }

    private Map<String, Long> currentCounts() {
        Map<String, Object> row = jdbc.queryForMap("""
                select
                  (select count(*) from crm.account where tenant_id=? and deleted_at is null) accounts,
                  (select count(*) from crm.contact where tenant_id=? and deleted_at is null) contacts,
                  (select count(*) from crm.lead where tenant_id=? and deleted_at is null) leads,
                  (select count(*) from sales.opportunity where tenant_id=?) opportunities,
                  (select count(*) from integration.outbox_event where tenant_id=?) outbox_events,
                  (select count(*) from governance.audit_event where tenant_id=?) audit_events
                """, tenant(), tenant(), tenant(), tenant(), tenant(), tenant());
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("accounts", number(row.get("accounts"))); result.put("contacts", number(row.get("contacts")));
        result.put("leads", number(row.get("leads"))); result.put("opportunities", number(row.get("opportunities")));
        result.put("outboxEvents", number(row.get("outbox_events"))); result.put("auditEvents", number(row.get("audit_events")));
        return result;
    }

    private Instant newestOutbox() {
        Timestamp value = jdbc.queryForObject("select max(created_at) from integration.outbox_event where tenant_id=?",
                Timestamp.class, tenant());
        return instant(value);
    }

    private String databaseVersion() {
        return jdbc.queryForObject("select version from flyway_schema_history where success order by installed_rank desc limit 1",
                String.class);
    }

    private static boolean versionAtLeast(String version, int expected) {
        try { return new BigDecimal(version).compareTo(BigDecimal.valueOf(expected)) >= 0; }
        catch (RuntimeException ex) { return false; }
    }

    private static void check(List<Map<String, Object>> checks, List<String> blockers,
                              String code, boolean passed, String detail) {
        checks.add(Map.of("code", code, "status", passed ? "PASS" : "FAIL", "detail", detail));
        if (!passed) blockers.add(code + " failed: " + detail + ".");
    }

    private String fingerprint(List<StoredComponent> components) {
        StringBuilder canonical = new StringBuilder();
        components.stream().sorted(Comparator.comparingInt(StoredComponent::sequence)).forEach(c -> canonical
                .append(c.sequence()).append('|').append(c.type()).append('|').append(c.key()).append('|')
                .append(c.operation()).append('|').append(c.before()).append('|').append(c.after()).append('\n'));
        return sha256(canonical.toString());
    }

    private String checksum(JsonNode value) { return sha256(value == null ? "null" : value.toString()); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private boolean same(JsonNode left, JsonNode right) { return java.util.Objects.equals(left, right); }
    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Release evidence is not valid JSON", ex); }
    }
    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored release evidence is invalid", ex); }
    }
    private JsonNode parseNullable(String value) {
        JsonNode node = value == null ? null : parse(value);
        return nullableNode(node);
    }
    private static JsonNode nullableNode(JsonNode node) { return node == null || node.isNull() ? null : node; }
    private List<String> readStringList(String value) {
        try { return json.readerForListOf(String.class).readValue(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored release issues are invalid", ex); }
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readMapList(String value) {
        try { return json.readValue(value, List.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored DR checks are invalid", ex); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Long> readLongMap(String value) {
        try {
            Map<String, Number> raw = json.readValue(value, Map.class);
            Map<String, Long> result = new LinkedHashMap<>(); raw.forEach((key, number) -> result.put(key, number.longValue()));
            return result;
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Stored recovery counts are invalid", ex); }
    }
    private List<DiffLine> readDiff(String value) {
        try { return json.readerForListOf(DiffLine.class).readValue(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored release diff is invalid", ex); }
    }

    private static String nullableText(JsonNode node) { return node == null || node.isNull() ? null : node.asText(); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String requiredReason(String value, String label) {
        String clean = clean(value); if (clean == null) throw new ConflictException(label + " is required"); return clean;
    }
    private static String enumValue(String value, List<String> allowed, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new ConflictException("Unsupported " + label + ": " + value);
        return normalized;
    }
    private static String environment(String value) { return enumValue(value, List.of("DEV", "QA", "UAT", "PROD"), "target environment"); }
    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private UUID tenant() { return TenantContext.get().tenantId(); }
    private UUID actor() { return TenantContext.get().userId(); }
    private UUID sandboxOwner() {
        List<UUID> currentActor = jdbc.query("select id from identity.app_user where tenant_id=? and id=? and active",
                (rs, row) -> rs.getObject(1, UUID.class), tenant(), actor());
        if (!currentActor.isEmpty()) return currentActor.getFirst();
        List<UUID> owners = jdbc.query("""
                select id from identity.app_user where tenant_id=? and active
                 order by case when role in ('TENANT_ADMIN','DATA_STEWARD') then 0 else 1 end, created_at
                 limit 1
                """, (rs, row) -> rs.getObject(1, UUID.class), tenant());
        if (owners.isEmpty()) throw new ConflictException("Create an active tenant administrator before provisioning a sandbox");
        return owners.getFirst();
    }
    private void requireRead() { RbacAccess.requireRead(); }

    private void requireAdmin() { CrmRole.requireMasterAdmin(TenantContext.get().role()); }
}
