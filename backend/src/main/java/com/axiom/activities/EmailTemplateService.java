package com.axiom.activities;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Immutable email-template versioning for FR-ACT-008. */
@Service
public class EmailTemplateService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public EmailTemplateService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record TemplateRow(UUID id, String apiName, String name, String folder, String description,
                              String sharingScope, boolean active, int currentVersion, String subject,
                              String body, List<String> mergeFields, String changeNote,
                              UUID ownerId, String ownerName, boolean canEdit, Instant updatedAt) {}

    public record CreateRequest(@NotBlank @Size(max = 120) String apiName,
                                @NotBlank @Size(max = 180) String name,
                                @Size(max = 120) String folder,
                                @Size(max = 1000) String description,
                                @NotBlank String sharingScope,
                                @NotBlank @Size(max = 240) String subject,
                                @NotBlank @Size(max = 20000) String body,
                                List<@Size(max = 100) String> mergeFields,
                                @Size(max = 500) String changeNote) {}

    public record ReviseRequest(@NotBlank @Size(max = 240) String subject,
                                @NotBlank @Size(max = 20000) String body,
                                List<@Size(max = 100) String> mergeFields,
                                @NotBlank @Size(max = 500) String changeNote) {}

    @Transactional(readOnly = true)
    public List<TemplateRow> list() {
        TenantContext.Principal p = TenantContext.get();
        boolean admin = isAdmin(p.role());
        return jdbc.query("""
                select t.id, t.api_name, t.name, t.folder, t.description, t.sharing_scope,
                       t.active, t.current_version, v.subject, v.body, v.merge_fields::text,
                       v.change_note, t.owner_id, u.display_name as owner_name, t.updated_at,
                       (?::boolean or t.owner_id = ? or exists (
                         select 1 from engagement.email_template_share s
                         where s.tenant_id = t.tenant_id and s.template_id = t.id
                           and s.role_code = ? and s.can_edit
                       )) as can_edit
                from engagement.email_template t
                join engagement.email_template_version v
                  on v.tenant_id = t.tenant_id and v.template_id = t.id
                 and v.version_no = t.current_version
                join identity.app_user u on u.tenant_id = t.tenant_id and u.id = t.owner_id
                where t.tenant_id = ? and t.active
                  and (?::boolean or t.owner_id = ? or t.sharing_scope = 'TENANT'
                       or exists (select 1 from engagement.email_template_share s
                                  where s.tenant_id = t.tenant_id and s.template_id = t.id
                                    and s.role_code = ?))
                order by t.folder, t.name
                """, (rs, i) -> new TemplateRow(
                rs.getObject("id", UUID.class), rs.getString("api_name"), rs.getString("name"),
                rs.getString("folder"), rs.getString("description"), rs.getString("sharing_scope"),
                rs.getBoolean("active"), rs.getInt("current_version"), rs.getString("subject"),
                rs.getString("body"), strings(rs.getString("merge_fields")), rs.getString("change_note"),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                !CrmRole.current(p.role()).readOnly() && rs.getBoolean("can_edit"),
                rs.getTimestamp("updated_at").toInstant()),
                admin, p.userId(), p.role(), p.tenantId(), admin, p.userId(), p.role());
    }

    @Transactional
    public TemplateRow create(CreateRequest request) {
        requireWrite();
        String apiName = required(request.apiName(), "Give the template an API name.")
                .toLowerCase(Locale.ROOT).replace('-', '_');
        if (!apiName.matches("^[a-z][a-z0-9_]*$")) {
            throw new ConflictException("API name must start with a letter and use lowercase letters, numbers or underscores.");
        }
        String sharing = scope(request.sharingScope());
        UUID tenant = TenantContext.get().tenantId();
        UUID actor = TenantContext.get().userId();
        UUID owner = localOwner(actor);
        UUID id = jdbc.queryForObject("""
                insert into engagement.email_template
                  (tenant_id, folder, api_name, name, description, sharing_scope, owner_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenant, blankTo(request.folder(), "General"), apiName,
                required(request.name(), "Give the template a name."), clean(request.description()),
                sharing, owner, actor);
        jdbc.update("""
                insert into engagement.email_template_version
                  (tenant_id, template_id, version_no, subject, body, merge_fields, change_note, created_by)
                values (?, ?, 1, ?, ?, ?::jsonb, ?, ?)
                """, tenant, id, required(request.subject(), "Add an email subject."),
                required(request.body(), "Add the email body."), json(request.mergeFields()),
                clean(request.changeNote()), actor);
        audit.record("EMAIL_TEMPLATE_CREATED", "EMAIL_TEMPLATE", id,
                "Created email template " + request.name(), Map.of("apiName", apiName, "version", 1));
        outbox.write("email_template", id, "email-template.created", Map.of("apiName", apiName, "version", 1));
        return find(id);
    }

    @Transactional
    public TemplateRow revise(UUID id, ReviseRequest request) {
        requireWrite();
        TemplateLock template = lock(id);
        requireEdit(template);
        int version = template.currentVersion() + 1;
        jdbc.update("""
                insert into engagement.email_template_version
                  (tenant_id, template_id, version_no, subject, body, merge_fields, change_note, created_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, tenantId(), id, version, required(request.subject(), "Add an email subject."),
                required(request.body(), "Add the email body."), json(request.mergeFields()),
                required(request.changeNote(), "Explain what changed in this version."), userId());
        jdbc.update("""
                update engagement.email_template
                set current_version = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, version, tenantId(), id);
        audit.record("EMAIL_TEMPLATE_REVISED", "EMAIL_TEMPLATE", id,
                "Created version " + version + " of " + template.name(),
                Map.of("previousVersion", template.currentVersion(), "version", version,
                        "changeNote", request.changeNote()));
        outbox.write("email_template", id, "email-template.revised", Map.of("version", version));
        return find(id);
    }

    private TemplateRow find(UUID id) {
        return list().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Email template not found"));
    }

    private record TemplateLock(UUID id, String name, UUID ownerId, int currentVersion, boolean sharedEdit) {}

    private TemplateLock lock(UUID id) {
        List<TemplateLock> rows = jdbc.query("""
                select t.id, t.name, t.owner_id, t.current_version,
                       exists (select 1 from engagement.email_template_share s
                               where s.tenant_id = t.tenant_id and s.template_id = t.id
                                 and s.role_code = ? and s.can_edit) as shared_edit
                from engagement.email_template t
                where t.tenant_id = ? and t.id = ? and t.active
                for update
                """, (rs, i) -> new TemplateLock(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("owner_id", UUID.class), rs.getInt("current_version"),
                rs.getBoolean("shared_edit")), TenantContext.get().role(), tenantId(), id);
        return rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Email template not found"));
    }

    private void requireEdit(TemplateLock row) {
        if (!isAdmin(TenantContext.get().role()) && !row.ownerId().equals(userId()) && !row.sharedEdit()) {
            throw new ForbiddenException("You can read this template but cannot create a new version of it.");
        }
    }

    private void requireWrite() {
        if (CrmRole.current(TenantContext.get().role()).readOnly()) {
            throw new ForbiddenException("Your role can read email templates but cannot change them.");
        }
    }

    /** Platform administrators act in a tenant without becoming a tenant user. */
    private UUID localOwner(UUID actor) {
        List<UUID> local = jdbc.query("""
                select id from identity.app_user
                where tenant_id = ? and active and id = ?
                """, (rs, i) -> rs.getObject("id", UUID.class), tenantId(), actor);
        if (!local.isEmpty()) return local.getFirst();
        return jdbc.query("""
                select id from identity.app_user
                where tenant_id = ? and active and role in ('TENANT_ADMIN','SALES_MANAGER')
                order by case role when 'TENANT_ADMIN' then 0 else 1 end, created_at
                limit 1
                """, (rs, i) -> rs.getObject("id", UUID.class), tenantId()).stream().findFirst()
                .orElseThrow(() -> new ConflictException(
                        "This workspace needs an active tenant administrator to own shared templates."));
    }

    private static boolean isAdmin(String role) {
        return "SUPER_ADMIN".equals(role) || "TENANT_ADMIN".equals(role);
    }

    private static String scope(String value) {
        String normalized = required(value, "Choose who can use the template.").toUpperCase(Locale.ROOT);
        if (!List.of("PRIVATE", "TENANT", "ROLE").contains(normalized)) {
            throw new ConflictException("Sharing scope must be PRIVATE, TENANT or ROLE.");
        }
        return normalized;
    }

    private String json(List<String> fields) {
        try {
            return json.writeValueAsString(fields == null ? List.of() : fields.stream()
                    .map(EmailTemplateService::clean).filter(value -> value != null).distinct().toList());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Merge fields could not be saved", ex);
        }
    }

    private List<String> strings(String value) {
        try {
            return json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored merge fields are not readable", ex);
        }
    }

    private static String required(String value, String message) {
        String cleaned = clean(value);
        if (cleaned == null) throw new ConflictException(message);
        return cleaned;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankTo(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null ? fallback : cleaned;
    }

    private static UUID tenantId() { return TenantContext.get().tenantId(); }
    private static UUID userId() { return TenantContext.get().userId(); }
}
