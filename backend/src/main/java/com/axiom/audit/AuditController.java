package com.axiom.audit;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditService audit;
    private final AuditChainService chain;
    private final FieldHistoryService fieldHistory;
    private final ReadAuditService readAudit;
    private final ExportAuditService exportAudit;
    private final AuthenticationAuditService authAudit;
    private final AuditRetentionService retention;

    public AuditController(AuditService audit, AuditChainService chain, FieldHistoryService fieldHistory,
                           ReadAuditService readAudit, ExportAuditService exportAudit,
                           AuthenticationAuditService authAudit, AuditRetentionService retention) {
        this.audit = audit;
        this.chain = chain;
        this.fieldHistory = fieldHistory;
        this.readAudit = readAudit;
        this.exportAudit = exportAudit;
        this.authAudit = authAudit;
        this.retention = retention;
    }

    /**
     * The record-level audit drawer used across the product. Left ungated and
     * unchanged: every role may see the trail for records it can already see.
     */
    @GetMapping
    public List<AuditService.AuditRow> list(@RequestParam(required = false) String entityType,
                                            @RequestParam(defaultValue = "50") int limit) {
        return audit.list(entityType, limit);
    }

    /** Filterable trail including each event's position in the tamper-evidence chain. */
    @GetMapping("/events")
    public List<AuditService.ChainedAuditRow> events(@RequestParam(required = false) String entityType,
                                                     @RequestParam(required = false) String action,
                                                     @RequestParam(required = false) String actor,
                                                     @RequestParam(required = false) String source,
                                                     @RequestParam(defaultValue = "100") int limit) {
        GovernanceAccess.requireRead();
        return audit.search(entityType, action, actor, source, limit);
    }

    /** Non-mutating chain status, safe to poll. */
    @GetMapping("/chain")
    public AuditChainService.ChainVerification chainStatus() {
        GovernanceAccess.requireRead();
        return chain.verifyOnly();
    }

    /** Verification of record, which is itself an audited action. */
    @PostMapping("/chain/verify")
    public AuditChainService.ChainVerification verifyChain() {
        GovernanceAccess.requireRead();
        return chain.verify();
    }

    @GetMapping("/field-history")
    public List<FieldHistoryService.FieldChange> fieldHistory(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String field,
            @RequestParam(defaultValue = "100") int limit) {
        GovernanceAccess.requireRead();
        return fieldHistory.history(entityType, entityId, field, limit);
    }

    @GetMapping("/reads")
    public List<ReadAuditService.ReadEvent> reads(@RequestParam(defaultValue = "100") int limit) {
        GovernanceAccess.requireRead();
        return readAudit.list(limit);
    }

    @GetMapping("/exports")
    public List<ExportAuditService.ExportEvent> exports(@RequestParam(defaultValue = "100") int limit) {
        GovernanceAccess.requireRead();
        return exportAudit.list(limit);
    }

    @GetMapping("/authentication")
    public List<AuthenticationAuditService.AuthEvent> authentication(@RequestParam(defaultValue = "100") int limit) {
        GovernanceAccess.requireRead();
        return authAudit.list(limit);
    }

    @GetMapping("/sensitive-fields")
    public List<ReadAuditService.SensitiveField> sensitiveFields() {
        GovernanceAccess.requireRead();
        return readAudit.registry();
    }

    @GetMapping("/retention")
    public AuditRetentionService.AuditRetention retention() {
        GovernanceAccess.requireRead();
        return retention.current();
    }

    @PutMapping("/retention")
    public AuditRetentionService.AuditRetention updateRetention(
            @Valid @RequestBody AuditRetentionService.AuditRetentionRequest request) {
        GovernanceAccess.requireWrite();
        return retention.update(request);
    }
}
