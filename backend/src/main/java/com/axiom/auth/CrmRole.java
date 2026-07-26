package com.axiom.auth;

import com.axiom.common.ForbiddenException;

import java.util.Arrays;
import java.util.List;

/** Central role catalogue. API and UI consume this same policy contract. */
public enum CrmRole {
    SUPER_ADMIN("Platform", "Read/write administration across every tenant", true, false, true, true, true),
    SUPER_AUDIT("Platform", "Read-only audit and reporting across every tenant", true, true, true, false, false),
    TENANT_ADMIN("Tenant", "Full administration inside one tenant", false, false, true, true, true),
    SALES_MANAGER("Tenant", "Sales team, pipeline, account and lead management", false, false, true, false, false),
    SALES("Tenant", "Owned sales execution and customer records", false, false, true, false, false),
    MARKETING("Tenant", "Campaign, segment and lead operations", false, false, true, false, false),
    SERVICE("Tenant", "Cases, accounts, contacts and entitlements", false, false, true, false, false),
    OPERATIONS("Tenant", "Revenue operations and governed reporting", false, false, true, false, false),
    FINANCE("Tenant", "Commercial and financial review", false, false, true, false, false),
    DATA_STEWARD("Tenant", "Validated master-data import and lifecycle management", false, false, true, true, true),
    AUDITOR("Tenant", "Read-only audit and reporting inside one tenant", false, true, true, false, false),
    INTEGRATION("Tenant", "Non-interactive API integration identity", false, false, false, false, false);

    private final String scope;
    private final String description;
    private final boolean platform;
    private final boolean readOnly;
    private final boolean exportAllowed;
    private final boolean importAllowed;
    private final boolean masterAdmin;

    CrmRole(String scope, String description, boolean platform, boolean readOnly,
            boolean exportAllowed, boolean importAllowed, boolean masterAdmin) {
        this.scope = scope;
        this.description = description;
        this.platform = platform;
        this.readOnly = readOnly;
        this.exportAllowed = exportAllowed;
        this.importAllowed = importAllowed;
        this.masterAdmin = masterAdmin;
    }

    public String scope() { return scope; }
    public String description() { return description; }
    public boolean platform() { return platform; }
    public boolean readOnly() { return readOnly; }
    public boolean exportAllowed() { return exportAllowed; }
    public boolean importAllowed() { return importAllowed; }
    public boolean masterAdmin() { return masterAdmin; }

    public static CrmRole current(String role) {
        try {
            return valueOf(role);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ForbiddenException("Unknown or disabled CRM role");
        }
    }

    public static void requirePlatform(String role) {
        if (!current(role).platform) throw new ForbiddenException("Platform role required");
    }

    /**
     * The write gate for any state-changing operation.
     *
     * <p>Defined in terms of {@code readOnly} rather than an allow-list of roles
     * that may write, and the direction matters: a role added to this enum in
     * future is writable unless it declares otherwise, but the two roles that
     * exist to observe — {@code AUDITOR} inside a tenant and {@code SUPER_AUDIT}
     * across the platform — are refused by the same flag that describes them.
     * An auditor with complete read and no write is a requirement of the tenant
     * role model, and this is where that promise is actually kept; a per-service
     * list of permitted roles would let it drift the first time someone adds a
     * service and forgets.
     *
     * <p>{@code INTEGRATION} is deliberately not read-only. It is the
     * non-interactive API identity and exists to write; what it may reach is
     * constrained by the scopes on its credential, not by this gate.
     */
    public static void requireWrite(String role) {
        if (current(role).readOnly) {
            throw new ForbiddenException("Your role has read-only access and cannot change records");
        }
    }

    public static void requireExport(String role) {
        if (!current(role).exportAllowed) throw new ForbiddenException("Your role does not permit data export");
    }

    public static void requireImport(String role) {
        if (!current(role).importAllowed) throw new ForbiddenException("Data Steward or administrator role required");
    }

    public static void requireMasterAdmin(String role) {
        if (!current(role).masterAdmin) throw new ForbiddenException("Master-data administrator role required");
    }

    public record Descriptor(String code, String scope, String description, boolean readOnly,
                             boolean exportAllowed, boolean importAllowed, boolean masterAdmin) {}

    public static List<Descriptor> catalogue() {
        return Arrays.stream(values()).map(role -> new Descriptor(
                role.name(), role.scope, role.description, role.readOnly,
                role.exportAllowed, role.importAllowed, role.masterAdmin)).toList();
    }
}
