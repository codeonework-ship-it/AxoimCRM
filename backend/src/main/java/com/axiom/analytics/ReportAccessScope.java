package com.axiom.analytics;

import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "what may this user see" into a SQL fragment that narrows a fact table.
 *
 * <h2>The projection aggregates; the authoritative store decides</h2>
 * ADR-008 decision 4, stated as an implementation rule: no fact table in this
 * module carries a materialized ACL, a sharing key or a permission bit, and
 * nothing here reads one. The scope is a semi-join from the fact table's business
 * key into the authoritative table, evaluated under the very same
 * {@link AuthorizationService} predicate the record list pages use:
 *
 * <pre>
 *   f.opportunity_id in (select auth.id from sales.opportunity auth
 *                         where auth.tenant_id = ? and (&lt;live predicate&gt;))
 * </pre>
 *
 * <p>It costs a semi-join against an indexed primary key, and it buys the property
 * the ADR calls the security-critical one: access changes take effect on the next
 * query rather than on the next projection run. Materialized permissions in the
 * read model would be faster and "would eventually show someone a record they had
 * just been removed from".
 *
 * <h2>Two shortcuts, both safe in the same direction</h2>
 * When the predicate allows everything (an administrator with view-all, or a
 * permissive org-wide default) the semi-join is omitted entirely — so the common
 * administrative report touches no OLTP table at all, which is the whole point of
 * ADR-008 decision 1. When it denies everything the query is short-circuited to
 * zero rows without running at all. Both shortcuts move in the direction of
 * showing less, never more.
 *
 * <h2>Restriction is reported, not hidden</h2>
 * {@code restricted} travels back to the caller so the UI can say "computed over
 * the records you may see" rather than presenting a silently under-reported total
 * as though it were the tenant's. Doc 14 §5 requires exactly that: "where access
 * restricts a roll-up, the restriction is indicated rather than silently
 * under-reported".
 */
@Component
public class ReportAccessScope {

    /**
     * @param sql        a boolean fragment referencing the fact-table alias, or null for "no restriction"
     * @param args       bind values for {@code sql}, in order
     * @param deniesAll  true when the caller may read nothing of this dataset at all
     * @param restricted true when the result is narrower than the tenant's full data
     */
    public record Scope(String sql, List<Object> args, boolean deniesAll, boolean restricted) {

        public static final Scope UNRESTRICTED = new Scope(null, List.of(), false, false);
        public static final Scope DENY_ALL = new Scope("false", List.of(), true, true);

        public boolean hasClause() { return sql != null; }
    }

    private final AuthorizationService authorization;

    public ReportAccessScope(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /**
     * @param alias the fact table's alias in the caller's query (validated by the caller —
     *              this module only ever passes the literal {@code "f"})
     */
    public Scope scopeFor(AnalyticsDataset dataset, String alias) {
        return dataset.securable()
                .map(object -> semiJoin(alias + "." + dataset.idColumn(), object))
                .orElseGet(() -> derivedScope(dataset, alias));
    }

    /** Semi-join into the authoritative table under the live read predicate. */
    private Scope semiJoin(String factKeyExpression, SecurableObject object) {
        AuthorizationService.RecordPredicate predicate =
                authorization.visibleRecordPredicate(object, "auth");
        if (predicate.deniesEverything()) return Scope.DENY_ALL;
        if (predicate.allowsEverything()) return Scope.UNRESTRICTED;

        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        args.addAll(predicate.args());
        String sql = factKeyExpression + " in (select auth.id from " + object.qualifiedTable() + " auth"
                + " where auth.tenant_id = ?"
                + (object.softDeleted() ? " and auth.deleted_at is null" : "")
                + " and (" + predicate.sql() + "))";
        return new Scope(sql, List.copyOf(args), false, true);
    }

    /**
     * Datasets with no {@link SecurableObject} of their own.
     *
     * <p>Activities are not in the securable registry, so there is no live predicate
     * to borrow. Rather than inventing one — which would be a second, divergent
     * implementation of the sharing model, the exact drift ADR-008 warns about —
     * this falls back to the conservative pair a CRM can defend: records I own, and
     * records on an account I am permitted to read. It can under-report an activity
     * on an account the caller cannot read; it cannot over-report one.
     */
    private Scope derivedScope(AnalyticsDataset dataset, String alias) {
        if (dataset != AnalyticsDataset.ACTIVITY) return Scope.DENY_ALL;

        Scope accountScope = semiJoin(alias + ".account_id", SecurableObject.ACCOUNT);
        if (accountScope.deniesAll()) {
            // No account read at all: the caller sees only what they own.
            return new Scope(alias + ".owner_id = ?", List.of(TenantContext.get().userId()), false, true);
        }
        if (!accountScope.hasClause()) return Scope.UNRESTRICTED;

        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().userId());
        args.addAll(accountScope.args());
        return new Scope("(" + alias + ".owner_id = ? or " + accountScope.sql() + ")",
                List.copyOf(args), false, true);
    }
}
