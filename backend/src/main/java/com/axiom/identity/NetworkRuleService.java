package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Login restriction by IP range (FR-TEN-014).
 *
 * <p><b>An empty allowlist permits everything.</b> That is the only safe default:
 * the alternative — "no rules means no access" — would lock every existing
 * workspace out the moment this table shipped. A tenant opts in by activating a
 * rule, and the seeded example ranges in V12 are deliberately inactive for the
 * same reason.
 *
 * <p>Matching is done on raw address bytes rather than by string prefix, so
 * {@code 10.0.0.0/12} does not accidentally match {@code 10.16.0.1} and an
 * IPv4-mapped IPv6 address is compared against IPv4 rules correctly.
 */
@Service
public class NetworkRuleService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public NetworkRuleService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record NetworkRule(UUID id, String cidr, String description, boolean active, Instant updatedAt) {}

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * @return true when the address is permitted: either no active rule exists, or
     *         at least one active rule contains the address
     */
    @Transactional(readOnly = true)
    public boolean isPermitted(UUID tenantId, String ip) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        List<String> active = jdbc.queryForList(
                "select cidr::text from identity.network_rule where tenant_id = ? and active = true",
                String.class, tenantId);
        if (active.isEmpty()) return true;
        if (ip == null || ip.isBlank()) {
            // An active allowlist with no resolvable client address cannot be
            // evaluated. Refusing is the conservative reading of FR-TEN-014.
            return false;
        }
        return active.stream().anyMatch(rule -> contains(rule, ip));
    }

    /** Visible for testing: does {@code cidr} contain {@code ip}? */
    public static boolean contains(String cidr, String ip) {
        try {
            String[] parts = cidr.trim().split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : network.getAddress().length * 8;
            InetAddress candidate = InetAddress.getByName(ip.trim());
            byte[] networkBytes = network.getAddress();
            byte[] candidateBytes = candidate.getAddress();
            if (networkBytes.length != candidateBytes.length) return false;
            if (prefix < 0 || prefix > networkBytes.length * 8) return false;
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != candidateBytes[i]) return false;
            }
            int remainingBits = prefix % 8;
            if (remainingBits == 0) return true;
            int mask = (0xff << (8 - remainingBits)) & 0xff;
            return (networkBytes[fullBytes] & mask) == (candidateBytes[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Administration
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<NetworkRule> list() {
        return jdbc.query("""
                select id, cidr::text as cidr, description, active, updated_at
                from identity.network_rule where tenant_id = ?
                order by active desc, cidr
                """, (rs, i) -> new NetworkRule(
                rs.getObject("id", UUID.class), rs.getString("cidr"), rs.getString("description"),
                rs.getBoolean("active"), rs.getTimestamp("updated_at").toInstant()),
                TenantContext.get().tenantId());
    }

    @Transactional
    public NetworkRule create(String cidr, String description, boolean active) {
        requireAdmin();
        TenantContext.Principal principal = TenantContext.get();
        String cleaned = cidr == null ? "" : cidr.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Give a network range in CIDR notation, for example 203.0.113.0/24");
        }
        // Fail on unparseable notation here rather than letting PostgreSQL raise a
        // 500 later: the caller gets a message they can act on.
        assertParsable(cleaned);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into identity.network_rule(id, tenant_id, cidr, description, active, created_by)
                values (?, ?, ?::cidr, ?, ?, ?)
                """, id, principal.tenantId(), cleaned,
                description == null || description.isBlank() ? "Added from the security screen" : description.trim(),
                active, principal.userId());
        audit.record("NETWORK_RULE_CREATE", "NETWORK_RULE", id,
                "Sign-in network rule added for " + cleaned,
                Map.of("cidr", cleaned, "active", active));
        return list().stream().filter(rule -> rule.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("The network rule was not found after creation"));
    }

    @Transactional
    public void setActive(UUID id, boolean active, String callerIp) {
        requireAdmin();
        TenantContext.Principal principal = TenantContext.get();
        String cidr = jdbc.query("select cidr::text from identity.network_rule where tenant_id = ? and id = ?",
                rs -> rs.next() ? rs.getString(1) : null, principal.tenantId(), id);
        if (cidr == null) throw new NotFoundException("That network rule no longer exists");
        if (active) {
            // Activating the first rule from outside its own range would lock the
            // activating administrator out on their next sign-in. Refuse rather
            // than let someone discover that the hard way.
            boolean anyActive = !jdbc.queryForList(
                    "select 1 from identity.network_rule where tenant_id = ? and active = true and id <> ?",
                    Integer.class, principal.tenantId(), id).isEmpty();
            if (!anyActive && callerIp != null && !contains(cidr, callerIp)) {
                throw new ForbiddenException("Activating " + cidr + " as the only rule would block your own "
                        + "address (" + callerIp + ") from signing in. Add a rule covering your address first.");
            }
        }
        jdbc.update("update identity.network_rule set active = ?, updated_at = now() where tenant_id = ? and id = ?",
                active, principal.tenantId(), id);
        audit.record(active ? "NETWORK_RULE_ACTIVATE" : "NETWORK_RULE_DEACTIVATE", "NETWORK_RULE", id,
                "Sign-in network rule " + (active ? "activated" : "deactivated") + " for " + cidr,
                Map.of("cidr", cidr));
    }

    @Transactional
    public void delete(UUID id) {
        requireAdmin();
        TenantContext.Principal principal = TenantContext.get();
        int deleted = jdbc.update("delete from identity.network_rule where tenant_id = ? and id = ?",
                principal.tenantId(), id);
        if (deleted == 0) throw new NotFoundException("That network rule no longer exists");
        audit.record("NETWORK_RULE_DELETE", "NETWORK_RULE", id, "Sign-in network rule removed", Map.of());
    }

    private static void assertParsable(String cidr) {
        String[] parts = cidr.split("/");
        try {
            InetAddress.getByName(parts[0]);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("\"" + cidr + "\" is not a network address. "
                    + "Use CIDR notation, for example 203.0.113.0/24.");
        }
        if (parts.length > 1) {
            try {
                Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + cidr + "\" has an invalid prefix length after the slash.");
            }
        }
    }

    private static void requireAdmin() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN) {
            throw new ForbiddenException("Managing sign-in network rules requires Super Admin or Tenant Admin");
        }
    }
}
