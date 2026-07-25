package com.axiom.admin;

import com.axiom.auth.CrmRole;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class RbacService {
    private final JdbcTemplate jdbc;

    public RbacService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ScreenPolicy(String roleCode, String screenCode, String moduleCode, String route,
                               String displayName, String description, boolean canRead,
                               boolean canWrite, boolean canExport, boolean canAdmin, String scope) {}

    public List<ScreenPolicy> policies(String roleCode) {
        String requested = clean(roleCode);
        if (requested == null) requested = TenantContext.get().role();
        CrmRole.current(requested);
        return jdbc.query("""
                select p.role_code, s.screen_code, s.module_code, s.route, s.display_name,
                       s.description, p.can_read, p.can_write, p.can_export, p.can_admin, p.scope
                from governance.rbac_policy p
                join governance.screen_catalog s on s.screen_code = p.screen_code
                where p.role_code = ? and s.active = true and p.can_read = true
                order by s.sort_order
                """, (rs, i) -> new ScreenPolicy(
                rs.getString("role_code"),
                rs.getString("screen_code"),
                rs.getString("module_code"),
                rs.getString("route"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getBoolean("can_read"),
                rs.getBoolean("can_write"),
                rs.getBoolean("can_export"),
                rs.getBoolean("can_admin"),
                rs.getString("scope")
        ), requested);
    }

    public boolean canWrite(String screenCode) {
        Boolean allowed = jdbc.queryForObject("""
                select coalesce(max(case when can_write then 1 else 0 end), 0) = 1
                from governance.rbac_policy
                where role_code = ? and screen_code = ?
                """, Boolean.class, TenantContext.get().role(), screenCode);
        return Boolean.TRUE.equals(allowed);
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned.toUpperCase();
    }
}
