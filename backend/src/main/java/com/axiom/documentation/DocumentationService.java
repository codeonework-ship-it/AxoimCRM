package com.axiom.documentation;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Governed source of truth for the tenant documentation drawer. */
@Service
public class DocumentationService {
    private static final String DRAWER_CODE = "USER_MANUAL";
    private static final List<String> SECTION_TYPES = List.of("CALLOUT", "STEPS", "SHORTCUTS", "RULE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public DocumentationService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record LocalizedText(@NotBlank @Size(max = 240) String title,
                                @Size(max = 4000) String body) {}
    public record DrawerText(@NotBlank @Size(max = 120) String eyebrow,
                             @NotBlank @Size(max = 180) String title) {}
    public record DrawerEntry(UUID id, String code, String marker, String title, String body, int sortOrder) {}
    public record DrawerSection(UUID id, String code, String type, String heading,
                                int sortOrder, List<DrawerEntry> entries) {}
    public record DrawerView(UUID id, String code, int version, String eyebrow, String title,
                             List<DrawerSection> sections) {}

    public record MasterEntry(UUID id, String code, String marker, int sortOrder, boolean active,
                              Map<String, LocalizedText> translations) {}
    public record MasterSection(UUID id, String code, String type, int sortOrder, boolean active,
                                Map<String, String> headings, List<MasterEntry> entries) {}
    public record MasterView(UUID id, String code, int version, boolean active,
                             Map<String, DrawerText> translations, List<MasterSection> sections) {}

    public record DrawerUpdate(@NotEmpty Map<String, @Valid DrawerText> translations,
                               boolean active,
                               @NotBlank @Size(max = 500) String changeNote) {}
    public record SectionRequest(@NotBlank @Size(max = 80) String code,
                                 @NotBlank String type,
                                 @Min(1) @Max(10000) int sortOrder,
                                 boolean active,
                                 @NotEmpty Map<String, @Size(max = 180) String> headings,
                                 @NotBlank @Size(max = 500) String changeNote) {}
    public record EntryRequest(UUID sectionId,
                               @NotBlank @Size(max = 80) String code,
                               @Size(max = 30) String marker,
                               @Min(1) @Max(10000) int sortOrder,
                               boolean active,
                               @NotEmpty Map<String, @Valid LocalizedText> translations,
                               @NotBlank @Size(max = 500) String changeNote) {}

    @Transactional(readOnly = true)
    public DrawerView drawer(String requestedLocale) {
        MasterView master = master(false);
        String locale = readLocale(requestedLocale);
        DrawerText copy = localized(master.translations(), locale, "The user manual has no English translation.");
        List<DrawerSection> sections = master.sections().stream().filter(MasterSection::active).map(section -> {
            String heading = localizedHeading(section.headings(), locale);
            List<DrawerEntry> entries = section.entries().stream().filter(MasterEntry::active).map(entry -> {
                LocalizedText text = localized(entry.translations(), locale,
                        "Documentation entry " + entry.code() + " has no English translation.");
                return new DrawerEntry(entry.id(), entry.code(), entry.marker(), text.title(), text.body(), entry.sortOrder());
            }).toList();
            return new DrawerSection(section.id(), section.code(), section.type(), heading,
                    section.sortOrder(), entries);
        }).toList();
        return new DrawerView(master.id(), master.code(), master.version(), copy.eyebrow(), copy.title(), sections);
    }

    @Transactional(readOnly = true)
    public MasterView master(boolean includeInactive) {
        UUID tenant = tenantId();
        String predicate = includeInactive ? "" : " and active";
        List<MasterSeed> masters = jdbc.query("""
                select id, code, current_version, active
                from documentation.drawer_master
                where tenant_id = ? and code = ?
                """ + predicate, (rs, row) -> new MasterSeed(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getInt("current_version"), rs.getBoolean("active")), tenant, DRAWER_CODE);
        MasterSeed seed = masters.stream().findFirst()
                .orElseThrow(() -> new NotFoundException("The user manual is not configured for this workspace."));

        Map<String, DrawerText> drawerTranslations = new LinkedHashMap<>();
        jdbc.query("""
                select locale_code, eyebrow, title from documentation.drawer_translation
                where tenant_id = ? and drawer_id = ? order by locale_code
                """, rs -> {
                    drawerTranslations.put(rs.getString("locale_code"),
                            new DrawerText(rs.getString("eyebrow"), rs.getString("title")));
                }, tenant, seed.id());

        Map<UUID, SectionBuilder> sections = new LinkedHashMap<>();
        jdbc.query("""
                select id, code, section_type, sort_order, active
                from documentation.drawer_section
                where tenant_id = ? and drawer_id = ?
                """ + (includeInactive ? "" : " and active") + " order by sort_order, code",
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    sections.put(id, new SectionBuilder(id, rs.getString("code"), rs.getString("section_type"),
                            rs.getInt("sort_order"), rs.getBoolean("active")));
                }, tenant, seed.id());
        if (!sections.isEmpty()) {
            jdbc.query("""
                    select t.section_id, t.locale_code, t.heading
                    from documentation.drawer_section_translation t
                    join documentation.drawer_section s on s.tenant_id=t.tenant_id and s.id=t.section_id
                    where t.tenant_id=? and s.drawer_id=? order by t.locale_code
                    """, rs -> {
                        SectionBuilder section = sections.get(rs.getObject("section_id", UUID.class));
                        if (section != null) section.headings.put(rs.getString("locale_code"), rs.getString("heading"));
                    }, tenant, seed.id());
            Map<UUID, EntryBuilder> entries = new LinkedHashMap<>();
            jdbc.query("""
                    select e.id, e.section_id, e.code, e.marker, e.sort_order, e.active
                    from documentation.drawer_entry e
                    join documentation.drawer_section s on s.tenant_id=e.tenant_id and s.id=e.section_id
                    where e.tenant_id=? and s.drawer_id=?
                    """ + (includeInactive ? "" : " and e.active") + " order by s.sort_order, e.sort_order, e.code",
                    rs -> {
                        UUID id = rs.getObject("id", UUID.class);
                        EntryBuilder entry = new EntryBuilder(id, rs.getObject("section_id", UUID.class),
                                rs.getString("code"), rs.getString("marker"), rs.getInt("sort_order"),
                                rs.getBoolean("active"));
                        entries.put(id, entry);
                        SectionBuilder section = sections.get(entry.sectionId);
                        if (section != null) section.entries.add(entry);
                    }, tenant, seed.id());
            if (!entries.isEmpty()) {
                jdbc.query("""
                        select t.entry_id, t.locale_code, t.title, t.body
                        from documentation.drawer_entry_translation t
                        join documentation.drawer_entry e on e.tenant_id=t.tenant_id and e.id=t.entry_id
                        join documentation.drawer_section s on s.tenant_id=e.tenant_id and s.id=e.section_id
                        where t.tenant_id=? and s.drawer_id=? order by t.locale_code
                        """, rs -> {
                            EntryBuilder entry = entries.get(rs.getObject("entry_id", UUID.class));
                            if (entry != null) entry.translations.put(rs.getString("locale_code"),
                                    new LocalizedText(rs.getString("title"), rs.getString("body")));
                        }, tenant, seed.id());
            }
        }
        return new MasterView(seed.id(), seed.code(), seed.version(), seed.active(), drawerTranslations,
                sections.values().stream().map(SectionBuilder::view).toList());
    }

    @Transactional
    public MasterView updateDrawer(DrawerUpdate request) {
        requireAdmin();
        validateTranslations(request.translations());
        UUID id = masterId(true);
        jdbc.update("update documentation.drawer_master set active=?, updated_at=now() where tenant_id=? and id=?",
                request.active(), tenantId(), id);
        request.translations().forEach((locale, text) -> jdbc.update("""
                insert into documentation.drawer_translation(tenant_id,drawer_id,locale_code,eyebrow,title)
                values (?,?,?,?,?) on conflict (tenant_id,drawer_id,locale_code)
                do update set eyebrow=excluded.eyebrow,title=excluded.title
                """, tenantId(), id, requireLocale(locale), required(text.eyebrow()), required(text.title())));
        return finish(id, "DOCUMENTATION_DRAWER_UPDATED", request.changeNote(), Map.of("active", request.active()));
    }

    @Transactional
    public MasterView createSection(SectionRequest request) {
        requireAdmin();
        validateHeadings(request.headings());
        UUID drawerId = masterId(true);
        UUID id = guarded(() -> jdbc.queryForObject("""
                insert into documentation.drawer_section(tenant_id,drawer_id,code,section_type,sort_order,active)
                values (?,?,?,?,?,?) returning id
                """, UUID.class, tenantId(), drawerId, code(request.code()), sectionType(request.type()),
                request.sortOrder(), request.active()));
        upsertHeadings(id, request.headings());
        return finish(drawerId, "DOCUMENTATION_SECTION_CREATED", request.changeNote(), Map.of("sectionId", id));
    }

    @Transactional
    public MasterView updateSection(UUID id, SectionRequest request) {
        requireAdmin();
        validateHeadings(request.headings());
        UUID drawerId = sectionDrawer(id);
        guarded(() -> jdbc.update("""
                update documentation.drawer_section set code=?, section_type=?, sort_order=?, active=?, updated_at=now()
                where tenant_id=? and id=?
                """, code(request.code()), sectionType(request.type()), request.sortOrder(), request.active(), tenantId(), id));
        upsertHeadings(id, request.headings());
        return finish(drawerId, "DOCUMENTATION_SECTION_UPDATED", request.changeNote(),
                Map.of("sectionId", id, "active", request.active()));
    }

    @Transactional
    public MasterView createEntry(EntryRequest request) {
        requireAdmin();
        validateTranslations(request.translations());
        if (request.sectionId() == null) throw new ConflictException("Choose the documentation section.");
        UUID drawerId = sectionDrawer(request.sectionId());
        UUID id = guarded(() -> jdbc.queryForObject("""
                insert into documentation.drawer_entry(tenant_id,section_id,code,marker,sort_order,active)
                values (?,?,?,?,?,?) returning id
                """, UUID.class, tenantId(), request.sectionId(), code(request.code()), clean(request.marker()),
                request.sortOrder(), request.active()));
        upsertEntryTranslations(id, request.translations());
        return finish(drawerId, "DOCUMENTATION_ENTRY_CREATED", request.changeNote(), Map.of("entryId", id));
    }

    @Transactional
    public MasterView updateEntry(UUID id, EntryRequest request) {
        requireAdmin();
        validateTranslations(request.translations());
        UUID drawerId = entryDrawer(id);
        UUID sectionId = request.sectionId() == null ? entrySection(id) : request.sectionId();
        UUID targetDrawerId = sectionDrawer(sectionId);
        if (!drawerId.equals(targetDrawerId)) {
            throw new ConflictException("An entry cannot be moved into a different documentation drawer.");
        }
        guarded(() -> jdbc.update("""
                update documentation.drawer_entry set section_id=?, code=?, marker=?, sort_order=?, active=?, updated_at=now()
                where tenant_id=? and id=?
                """, sectionId, code(request.code()), clean(request.marker()), request.sortOrder(), request.active(), tenantId(), id));
        upsertEntryTranslations(id, request.translations());
        return finish(drawerId, "DOCUMENTATION_ENTRY_UPDATED", request.changeNote(),
                Map.of("entryId", id, "active", request.active()));
    }

    private MasterView finish(UUID drawerId, String action, String note, Map<String, Object> details) {
        int version = jdbc.queryForObject("""
                update documentation.drawer_master set current_version=current_version+1, updated_at=now()
                where tenant_id=? and id=? returning current_version
                """, Integer.class, tenantId(), drawerId);
        MasterView snapshot = master(true);
        try {
            jdbc.update("""
                    insert into documentation.drawer_revision(tenant_id,drawer_id,version_no,snapshot,change_note,created_by)
                    values (?,?,?,?::jsonb,?,?)
                    """, tenantId(), drawerId, version, json.writeValueAsString(snapshot), required(note), userId());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Documentation revision could not be recorded", ex);
        }
        Map<String, Object> event = new LinkedHashMap<>(details);
        event.put("version", version);
        event.put("changeNote", note);
        audit.record(action, "DOCUMENTATION_DRAWER", drawerId, note, event);
        outbox.write("documentation_drawer", drawerId, action.toLowerCase(Locale.ROOT).replace('_', '-'), event);
        return snapshot;
    }

    private void upsertHeadings(UUID sectionId, Map<String, String> headings) {
        headings.forEach((locale, heading) -> jdbc.update("""
                insert into documentation.drawer_section_translation(tenant_id,section_id,locale_code,heading)
                values (?,?,?,?) on conflict (tenant_id,section_id,locale_code)
                do update set heading=excluded.heading
                """, tenantId(), sectionId, requireLocale(locale), clean(heading)));
    }

    private void upsertEntryTranslations(UUID entryId, Map<String, LocalizedText> translations) {
        translations.forEach((locale, text) -> jdbc.update("""
                insert into documentation.drawer_entry_translation(tenant_id,entry_id,locale_code,title,body)
                values (?,?,?,?,?) on conflict (tenant_id,entry_id,locale_code)
                do update set title=excluded.title,body=excluded.body
                """, tenantId(), entryId, requireLocale(locale), required(text.title()), clean(text.body())));
    }

    private UUID masterId(boolean includeInactive) { return master(includeInactive).id(); }
    private UUID sectionDrawer(UUID id) { return oneId("select drawer_id from documentation.drawer_section where tenant_id=? and id=?", id, "Documentation section not found."); }
    private UUID entryDrawer(UUID id) { return oneId("select s.drawer_id from documentation.drawer_entry e join documentation.drawer_section s on s.tenant_id=e.tenant_id and s.id=e.section_id where e.tenant_id=? and e.id=?", id, "Documentation entry not found."); }
    private UUID entrySection(UUID id) { return oneId("select section_id from documentation.drawer_entry where tenant_id=? and id=?", id, "Documentation entry not found."); }
    private UUID oneId(String sql, UUID id, String message) {
        return jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class), tenantId(), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException(message));
    }

    private void validateHeadings(Map<String, String> headings) {
        if (headings == null || !headings.containsKey("en")) throw new ConflictException("Add the English section heading (it may be blank for a callout or rule).");
        headings.keySet().forEach(this::requireLocale);
    }
    private void validateTranslations(Map<?, ?> translations) {
        if (translations == null || !translations.containsKey("en")) throw new ConflictException("An English translation is required.");
        translations.keySet().forEach(key -> requireLocale(String.valueOf(key)));
    }
    private String readLocale(String value) {
        String locale = clean(value) == null ? "en" : value.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        Integer count = jdbc.queryForObject("select count(*) from i18n.locale where code=? and active", Integer.class, locale);
        return count != null && count > 0 ? locale : "en";
    }
    private String requireLocale(String value) {
        String locale = clean(value) == null ? "" : value.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        Integer count = jdbc.queryForObject("select count(*) from i18n.locale where code=? and active", Integer.class, locale);
        if (count == null || count == 0) throw new ConflictException("Unsupported documentation locale: " + value);
        return locale;
    }
    private static String sectionType(String value) {
        String type = required(value).toUpperCase(Locale.ROOT);
        if (!SECTION_TYPES.contains(type)) throw new ConflictException("Section type must be CALLOUT, STEPS, SHORTCUTS or RULE.");
        return type;
    }
    private static String code(String value) {
        String code = required(value).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!code.matches("^[A-Z][A-Z0-9_]{2,79}$")) throw new ConflictException("Code must use 3-80 uppercase letters, numbers or underscores.");
        return code;
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String required(String value) { String clean = clean(value); if (clean == null) throw new ConflictException("A required value is missing."); return clean; }
    private void requireAdmin() { CrmRole.requireMasterAdmin(TenantContext.get().role()); }
    private static UUID tenantId() { return TenantContext.get().tenantId(); }
    private static UUID userId() { return TenantContext.get().userId(); }
    private static <T> T localized(Map<String, T> values, String locale, String missing) {
        T value = values.getOrDefault(locale, values.get("en"));
        if (value == null) throw new ConflictException(missing);
        return value;
    }
    private static String localizedHeading(Map<String, String> values, String locale) {
        String value = values.get(locale);
        return value != null ? value : values.get("en");
    }
    private static <T> T guarded(Work<T> work) {
        try { return work.run(); }
        catch (DataIntegrityViolationException ex) { throw new ConflictException("That documentation code or display order is already in use."); }
    }
    private static void guarded(WorkVoid work) {
        try { work.run(); }
        catch (DataIntegrityViolationException ex) { throw new ConflictException("That documentation code or display order is already in use."); }
    }
    @FunctionalInterface private interface Work<T> { T run(); }
    @FunctionalInterface private interface WorkVoid { void run(); }
    private record MasterSeed(UUID id, String code, int version, boolean active) {}
    private static final class SectionBuilder {
        final UUID id; final String code; final String type; final int order; final boolean active;
        final Map<String, String> headings = new LinkedHashMap<>(); final List<EntryBuilder> entries = new ArrayList<>();
        SectionBuilder(UUID id, String code, String type, int order, boolean active) { this.id=id; this.code=code; this.type=type; this.order=order; this.active=active; }
        MasterSection view() { return new MasterSection(id, code, type, order, active, headings,
                entries.stream().map(EntryBuilder::view).toList()); }
    }
    private static final class EntryBuilder {
        final UUID id; final UUID sectionId; final String code; final String marker; final int order; final boolean active;
        final Map<String, LocalizedText> translations = new LinkedHashMap<>();
        EntryBuilder(UUID id, UUID sectionId, String code, String marker, int order, boolean active) { this.id=id; this.sectionId=sectionId; this.code=code; this.marker=marker; this.order=order; this.active=active; }
        MasterEntry view() { return new MasterEntry(id, code, marker, order, active, translations); }
    }
}
