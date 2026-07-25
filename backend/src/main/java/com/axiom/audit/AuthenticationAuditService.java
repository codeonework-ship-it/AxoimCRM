package com.axiom.audit;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * FR-AUD-004 — authentication auditing: login success and failure, MFA challenges,
 * session creation and revocation, impersonation and break-glass.
 *
 * <p>None of those events are produced here. E01 owns identity and already writes
 * them: sign-in outcomes to {@code identity.login_attempt}, and MFA, session,
 * impersonation and break-glass actions to {@code governance.audit_event}. This
 * service is the <b>reader</b> that unifies the two into one authentication view,
 * because a compliance officer asked "who got into this account last month" should
 * not have to know that the answer lives in two tables with different shapes.
 *
 * <p>Deliberately no writes and no changes to E01's classes. Duplicating the
 * capture would mean two records of the same fact that can disagree — and in an
 * authentication log, disagreeing is worse than incomplete.
 */
@Service
public class AuthenticationAuditService {

    /** Audit actions from the identity module that are authentication events. */
    static final Set<String> AUTH_ACTIONS = Set.of(
            "MFA_ENROL_START", "MFA_ENROL_CONFIRM", "MFA_ENROL_FAILED", "MFA_DISABLE",
            "MFA_RECOVERY_REISSUE", "STEP_UP", "STEP_UP_REFUSED",
            "SESSION_REVOKE", "SESSION_REVOKE_ALL",
            "IMPERSONATION_START", "IMPERSONATION_STOP", "IMPERSONATION_REFUSED", "IMPERSONATION_CONSENT",
            "BREAK_GLASS_REQUEST", "BREAK_GLASS_USE", "BREAK_GLASS_REVOKE", "BREAK_GLASS_EXPIRED",
            "PASSWORD_CHANGE", "SSO_AUTHORIZE_BUILT",
            "SERVICE_CREDENTIAL_ISSUE", "SERVICE_CREDENTIAL_ROTATE", "SERVICE_CREDENTIAL_REVOKE",
            "SCIM_TOKEN_ISSUE", "SCIM_TOKEN_REVOKE");

    /**
     * @param category SIGN_IN, MFA, SESSION, IMPERSONATION, BREAK_GLASS or CREDENTIAL
     * @param outcome  SUCCESS or the specific failure reason; never a credential
     */
    public record AuthEvent(String category, String action, String outcome, String subject,
                            String actorName, String ip, String userAgent, String reason,
                            String correlationId, Instant at) {}

    private final JdbcTemplate jdbc;

    public AuthenticationAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AuthEvent> list(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        UUID tenantId = TenantContext.get().tenantId();
        // The union is built in SQL so the limit applies to the merged stream rather
        // than to each source, which would silently drop the older half.
        return jdbc.query("""
                select category, action, outcome, subject, actor_name, ip, user_agent, reason,
                       correlation_id, at
                from (
                  select 'SIGN_IN' as category,
                         case when outcome = 'SUCCESS' then 'LOGIN_SUCCESS' else 'LOGIN_FAILURE' end as action,
                         outcome, email as subject, email as actor_name, ip, user_agent,
                         null::text as reason, null::text as correlation_id, at
                    from identity.login_attempt
                   where tenant_id = ?
                  union all
                  select case
                           when action like 'MFA%' or action = 'STEP_UP' or action like 'STEP_UP%' then 'MFA'
                           when action like 'SESSION%' then 'SESSION'
                           when action like 'IMPERSONATION%' then 'IMPERSONATION'
                           when action like 'BREAK_GLASS%' then 'BREAK_GLASS'
                           when action like 'SERVICE_CREDENTIAL%' or action like 'SCIM_TOKEN%' then 'CREDENTIAL'
                           else 'SIGN_IN' end as category,
                         action,
                         case when action like '%REFUSED' or action like '%FAILED' then 'REFUSED' else 'SUCCESS' end as outcome,
                         entity_type || coalesce(' ' || entity_id::text, '') as subject,
                         actor_name, null::text as ip, null::text as user_agent, reason,
                         correlation_id, occurred_at as at
                    from governance.audit_event
                   where tenant_id = ? and action = any(?)
                ) merged
                order by at desc
                limit ?
                """, (rs, i) -> new AuthEvent(rs.getString("category"), rs.getString("action"),
                rs.getString("outcome"), rs.getString("subject"), rs.getString("actor_name"),
                rs.getString("ip"), rs.getString("user_agent"), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getTimestamp("at").toInstant()),
                tenantId, tenantId, AUTH_ACTIONS.toArray(String[]::new), limit);
    }

    /** Sink for any module that wants an authentication event audited by name. */
    public static boolean isAuthenticationAction(String action) {
        return action != null && AUTH_ACTIONS.contains(action.toUpperCase(Locale.ROOT));
    }
}
