package com.axiom.i18n;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the translation registry created in V10__i18n_translation_registry.sql.
 *
 * <h2>Why this endpoint accepts anonymous callers</h2>
 * The login screen has to be translated, so the bundle must be fetchable before
 * a token exists. {@code JwtAuthFilter} therefore treats {@code /api/v1/i18n/**}
 * as OPTIONALLY authenticated: no {@code Authorization} header skips the filter,
 * a header takes the normal verified path.
 *
 * With no tenant bound, {@code TenantSessionAspect} does not set
 * {@code app.tenant_id}, the RLS policy on
 * {@code i18n.tenant_translation_override} admits nothing, and the override
 * branch of the COALESCE below contributes no rows. Base product translations
 * still resolve, which is exactly what an unauthenticated caller should see — a
 * tenant's private relabelling is not public data. A signed-in caller gets its
 * own tenant's overrides, and only its own.
 *
 * <h2>Fallback chain</h2>
 * {@code tenant override -> base translation for the locale -> English}. The
 * English tail matters: a key added by a new epic and not yet translated into
 * German renders as English in the German UI instead of leaking
 * {@code nav.module.forecast} onto the screen.
 */
@Service
@Transactional(readOnly = true)
public class I18nService {

    /** The shipped default locale, and the tail of every fallback chain. */
    static final String DEFAULT_LOCALE = "en";

    private final JdbcTemplate jdbc;

    public I18nService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LocaleRow(String code, String englishName, String nativeName,
                            boolean isDefault, int sortOrder) {}

    public List<LocaleRow> locales() {
        return jdbc.query("""
                select code, english_name, native_name, is_default, sort_order
                from i18n.locale
                where active = true
                order by sort_order, code
                """, (rs, i) -> new LocaleRow(
                rs.getString("code"),
                rs.getString("english_name"),
                rs.getString("native_name"),
                rs.getBoolean("is_default"),
                rs.getInt("sort_order")));
    }

    /**
     * Flat {@code key_path -> value} map for one locale.
     *
     * The override predicate is written explicitly against the caller's tenant
     * rather than left entirely to RLS — ADR-001 asks for both enforcement
     * levels, and an anonymous caller must produce a definite "no tenant" rather
     * than relying on an unset session variable. {@code cast(? as uuid)} is
     * needed because the parameter can be null and PgJDBC will not infer a type
     * for an untyped NULL.
     */
    public Map<String, String> bundle(String localeCode) {
        String locale = requireKnownLocale(localeCode);
        String tenantId = TenantContext.isBound() ? TenantContext.get().tenantId().toString() : null;

        List<Map.Entry<String, String>> rows = jdbc.query("""
                select k.key_path,
                       coalesce(o.value, tl.value, te.value) as value
                from i18n.translation_key k
                left join i18n.tenant_translation_override o
                       on o.key_id = k.id
                      and o.locale_code = ?
                      and o.tenant_id = cast(? as uuid)
                left join i18n.translation tl
                       on tl.key_id = k.id and tl.locale_code = ?
                left join i18n.translation te
                       on te.key_id = k.id and te.locale_code = ?
                where coalesce(o.value, tl.value, te.value) is not null
                order by k.key_path
                """,
                (rs, i) -> Map.entry(rs.getString("key_path"), rs.getString("value")),
                locale, tenantId, locale, DEFAULT_LOCALE);

        // LinkedHashMap preserves the key_path ordering above, so two bundles can
        // be diffed by eye.
        Map<String, String> bundle = new LinkedHashMap<>();
        rows.forEach(entry -> bundle.put(entry.getKey(), entry.getValue()));
        return bundle;
    }

    /**
     * Exact {@code English source text -> resolved localized text} catalogue.
     *
     * <p>This is the compatibility boundary for older and high-density screens
     * whose visible labels pre-date explicit {@code t(key, fallback)} calls.
     * The browser only replaces an exact registered phrase; it never sends
     * record data to a translation vendor and never guesses at user content.
     * New screens should still use the keyed bundle directly.</p>
     *
     * <p>The same tenant-aware fallback chain as {@link #bundle(String)} is
     * deliberately retained. {@code putIfAbsent} makes duplicate English
     * wording deterministic: the first key in key-path order owns that phrase.</p>
     */
    public Map<String, String> phraseBundle(String localeCode) {
        String locale = requireKnownLocale(localeCode);
        String tenantId = TenantContext.isBound() ? TenantContext.get().tenantId().toString() : null;

        List<Map.Entry<String, String>> rows = jdbc.query("""
                select te.value as source_value,
                       coalesce(o.value, tl.value, te.value) as localized_value
                from i18n.translation_key k
                join i18n.translation te
                  on te.key_id = k.id and te.locale_code = ?
                left join i18n.tenant_translation_override o
                       on o.key_id = k.id
                      and o.locale_code = ?
                      and o.tenant_id = cast(? as uuid)
                left join i18n.translation tl
                       on tl.key_id = k.id and tl.locale_code = ?
                where coalesce(o.value, tl.value, te.value) is not null
                order by k.key_path
                """,
                (rs, i) -> Map.entry(rs.getString("source_value"), rs.getString("localized_value")),
                DEFAULT_LOCALE, locale, tenantId, locale);

        Map<String, String> phrases = new LinkedHashMap<>();
        rows.forEach(entry -> phrases.putIfAbsent(entry.getKey(), entry.getValue()));
        return phrases;
    }

    private String requireKnownLocale(String localeCode) {
        String normalized = localeCode == null ? "" : localeCode.trim().toLowerCase(Locale.ROOT);
        Boolean known = jdbc.queryForObject(
                "select exists(select 1 from i18n.locale where code = ? and active = true)",
                Boolean.class, normalized);
        if (known == null || !known) {
            throw new NotFoundException("Unsupported locale: " + localeCode);
        }
        return normalized;
    }
}
