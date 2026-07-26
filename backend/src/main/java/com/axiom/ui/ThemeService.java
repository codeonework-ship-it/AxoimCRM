package com.axiom.ui;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The theme catalogue and each user's chosen theme (V336).
 *
 * <h2>Why the catalogue is read-only here</h2>
 * A theme is half data and half CSS: a row in {@code reference.ui_theme} without
 * a matching {@code :root[data-theme="<code>"]} block in tokens.css is a theme
 * that resolves to no colours at all, and a CSS block without a row is a theme
 * nobody can reach. Only a deployment can add the CSS half, so only a migration
 * adds the row half — the two travel together. That is why this service exposes
 * no create or update for the catalogue, and why the migration revokes write on
 * the table from {@code axiom_app} rather than relying on nobody writing the
 * code for it.
 *
 * <h2>Why an unset preference stays unset</h2>
 * {@code theme_code} is nullable and a user who has never chosen is left null
 * rather than being stamped with today's default. It means changing the product
 * default moves every user who never expressed a preference, which is the whole
 * point of having a default; writing it in at first read would freeze thousands
 * of users onto whatever the default happened to be the day they signed in.
 *
 * <h2>Activity logging comes for free</h2>
 * Deliberately no bespoke audit call in here. {@code UserActivityFilter} records
 * one row per API request including the actor, the outcome and the status code,
 * so {@code PUT /api/v1/ui/theme} is already tracked as
 * {@code PUT /api/v1/ui/theme} against the user who made it. A second,
 * hand-written log line would be a copy that can disagree with the first.
 */
@Service
public class ThemeService {

    private final JdbcTemplate jdbc;

    public ThemeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One catalogue entry. {@code swatch} is ground, accent, AI mark. */
    public record Theme(String code, String name, String blurb, List<String> swatch,
                        String appearance, boolean isDefault, int sortOrder) {}

    /**
     * Everything the client needs in one round trip: the catalogue, the caller's
     * selection, and the fallback.
     *
     * <p>One request rather than three because the theme decides what the first
     * paint looks like. Two sequential requests would mean the shell renders in
     * the default theme and then switches, which is the flash the client's
     * pre-paint cache exists to avoid.
     *
     * @param selected the caller's chosen code, or null if they never chose
     * @param effective what to actually paint: the selection, else the default
     */
    public record ThemeState(List<Theme> themes, String selected, String defaultCode,
                             String effective) {}

    @Transactional(readOnly = true)
    public ThemeState state() {
        UUID tenantId = TenantContext.get().tenantId();
        UUID userId = TenantContext.get().userId();

        List<Theme> themes = catalogue();
        if (themes.isEmpty()) {
            // The catalogue is seeded by migration, so empty means the migration
            // did not run. Say that, rather than returning an empty picker and
            // letting the frontend look broken.
            throw new NotFoundException(
                    "No UI themes are configured. reference.ui_theme is empty — check that "
                    + "migration V336 has been applied to this environment.");
        }

        String defaultCode = themes.stream().filter(Theme::isDefault).findFirst()
                .map(Theme::code)
                // A single default is enforced by uq_ui_theme_single_default, but
                // is_default = false on every row is still representable, so fall
                // back to sort order rather than returning null.
                .orElseGet(() -> themes.get(0).code());

        List<String> selectedRows = jdbc.queryForList(
                "select theme_code from identity.user_ui_preference "
                + "where tenant_id = ? and user_id = ? and theme_code is not null",
                String.class, tenantId, userId);
        String selected = selectedRows.isEmpty() ? null : selectedRows.get(0);

        /*
         * A selection that is no longer in the active catalogue resolves to the
         * default instead of being echoed back. Retiring a theme has to actually
         * retire it — otherwise the users already on it keep it forever and the
         * is_active flag means nothing. The stored row is left alone: if the theme
         * is reactivated, their choice returns.
         */
        boolean stillOffered = selected != null
                && themes.stream().anyMatch(t -> t.code().equals(selected));

        return new ThemeState(themes, selected, defaultCode,
                stillOffered ? selected : defaultCode);
    }

    @Transactional(readOnly = true)
    public List<Theme> catalogue() {
        return jdbc.query(
                "select code, name, blurb, swatch, appearance, is_default, sort_order "
                + "from reference.ui_theme where is_active = true "
                + "order by sort_order, name",
                (rs, rowNum) -> {
                    String[] swatch = (String[]) rs.getArray("swatch").getArray();
                    return new Theme(
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("blurb"),
                            List.of(swatch),
                            rs.getString("appearance"),
                            rs.getBoolean("is_default"),
                            rs.getInt("sort_order"));
                });
    }

    /**
     * Record the caller's choice. Returns the state as it now stands, so the
     * client renders from the server's answer rather than from what it hoped it
     * had set.
     *
     * @param themeCode a code from the active catalogue, or null to clear the
     *                  choice and go back to following the product default
     */
    @Transactional
    public ThemeState choose(String themeCode) {
        UUID tenantId = TenantContext.get().tenantId();
        UUID userId = TenantContext.get().userId();

        String code = themeCode == null || themeCode.isBlank() ? null : themeCode.trim();

        /*
         * Checked here even though a foreign key already guards the column. The
         * FK's message names a constraint, and this refusal names the themes that
         * would have worked — and it also catches the case the FK cannot see: a
         * theme that exists but has been deactivated.
         */
        if (code != null) {
            List<Theme> offered = catalogue();
            boolean known = offered.stream().anyMatch(t -> t.code().equals(code));
            if (!known) {
                List<String> codes = new ArrayList<>();
                offered.forEach(t -> codes.add(t.code()));
                throw new NotFoundException("Unknown or retired theme '" + code
                        + "'. Available themes: " + String.join(", ", codes) + ".");
            }
        }

        jdbc.update(
                "insert into identity.user_ui_preference (tenant_id, user_id, theme_code, updated_at) "
                + "values (?, ?, ?, now()) "
                + "on conflict (tenant_id, user_id) "
                + "do update set theme_code = excluded.theme_code, updated_at = now()",
                tenantId, userId, code);

        return state();
    }

    /**
     * Catalogue plus how many users are on each theme. For the admin surface:
     * "can we retire Mark VII" is a question about who is using it, and the
     * answer has to come from the preference table rather than from a guess.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> adoption() {
        return jdbc.queryForList(
                "select t.code, t.name, t.appearance, t.is_default, t.is_active, "
                + "       count(p.user_id) as explicit_users "
                + "from reference.ui_theme t "
                + "left join identity.user_ui_preference p on p.theme_code = t.code "
                + "group by t.code, t.name, t.appearance, t.is_default, t.is_active, t.sort_order "
                + "order by t.sort_order, t.name");
    }
}
